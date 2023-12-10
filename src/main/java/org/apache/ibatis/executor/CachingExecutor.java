/*
 *    Copyright 2009-2023 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.executor;

import java.sql.SQLException;
import java.util.List;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.TransactionalCacheManager;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class CachingExecutor implements Executor {

  private final Executor delegate;
  private final TransactionalCacheManager tcm = new TransactionalCacheManager();

  public CachingExecutor(Executor delegate) {
    this.delegate = delegate;
    delegate.setExecutorWrapper(this);
  }

  @Override
  public Transaction getTransaction() {
    return delegate.getTransaction();
  }

  @Override
  public void close(boolean forceRollback) {
    try {
      // issues #499, #524 and #573
      if (forceRollback) {
        tcm.rollback();
      } else {
        tcm.commit();
      }
    } finally {
      delegate.close(forceRollback);
    }
  }

  @Override
  public boolean isClosed() {
    return delegate.isClosed();
  }

  @Override
  public int update(MappedStatement ms, Object parameterObject) throws SQLException {
    flushCacheIfRequired(ms);
    return delegate.update(ms, parameterObject);
  }

  @Override
  public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
    flushCacheIfRequired(ms);
    return delegate.queryCursor(ms, parameter, rowBounds);
  }

  @Override
  public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler)
      throws SQLException {
    // 首先根据传递的参数获取BoundSql对象，对于不同类型的SqlSource，对应的getBoundSql实现不同
    BoundSql boundSql = ms.getBoundSql(parameterObject);
    // 创建缓存key
    CacheKey key = createCacheKey(ms, parameterObject, rowBounds, boundSql);
    return query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
  }

  /**
   *  二级缓存是mapper级别的缓存，多个SqlSession去操作同一个mapper的sql语句，
   *  多个SqlSession可以共用二级缓存，二级缓存是跨SqlSession。
   *  二级缓存默认不启用，需要通过在Mapper中明确设置cache，它的实现在CachingExecutor的query()方法中
   *
   *  在mybatis的缓存实现中，缓存键CacheKey的格式为：
   *  cacheKey=ID + offset + limit + sql + parameterValues + environmentId。
   *  对于本例子中的语句，其CacheKey为：
   *  -1445574094:212285810:org.mybatis.internal.example.mapper.UserMapper.getUser:0:2147483647:
   *  select lfPartyId,partyName from LfParty where partyName = ?　AND partyName like ?
   *  and lfPartyId in ( ?, ?):p2:p2:1:2:development
   *
   *  对于一级缓存，commit/rollback都会清空一级缓存。
   *  对于二级缓存，DML操作或者显示设置语句层面的flushCache属性都会使得二级缓存失效。
   *
   *  在二级缓存容器的具体回收策略实现上，有下列几种：
   *  LRU – 最近最少使用的：移除最长时间不被使用的对象，也是默认的选项，其实现类是org.apache.ibatis.cache.decorators.LruCache。
   * FIFO – 先进先出：按对象进入缓存的顺序来移除它们，其实现类是org.apache.ibatis.cache.decorators.FifoCache。
   * SOFT – 软引用：移除基于垃圾回收器状态和软引用规则的对象，其实现类是org.apache.ibatis.cache.decorators.SoftCache。
   * WEAK – 弱引用：更积极地移除基于垃圾收集器状态和弱引用规则的对象，其实现类是org.apache.ibatis.cache.decorators.WeakCache。
   *
   * 在缓存的设计上，Mybatis的所有Cache算法都是基于装饰器/Composite模式对PerpetualCache扩展增加功能。
   *
   * 对于模块化微服务系统来说，应该来说mybatis的一二级缓存对业务数据都不适合，尤其是对于OLTP系统来说，
   * CRM/BI这些不算，如果要求数据非常精确的话，也不是特别合适。对这些要求数据准确的系统来说，
   * 尽可能只使用mybatis的ORM特性比较靠谱。但是有一部分数据如果前期没有很少的设计缓存的话，是很有价值的，
   * 比如说对于一些配置类数据比如数据字典、系统参数、业务配置项等很少变化的数据。
   */
  @Override
  public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler,
      CacheKey key, BoundSql boundSql) throws SQLException {
    Cache cache = ms.getCache();
    if (cache != null) {
      // 如果需要刷新缓存(默认DML需要刷新,也可以语句层面修改)
      flushCacheIfRequired(ms);
      if (ms.isUseCache() && resultHandler == null) {
        ensureNoOutParams(ms, boundSql);
        @SuppressWarnings("unchecked")
        // 如果二级缓存中找到了记录就直接返回,否则到DB查询后进行缓存
        List<E> list = (List<E>) tcm.getObject(cache, key);
        if (list == null) {
          list = delegate.query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
          tcm.putObject(cache, key, list); // issue #578 and #116
        }
        return list;
      }
    }
    return delegate.query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
  }

  @Override
  public List<BatchResult> flushStatements() throws SQLException {
    return delegate.flushStatements();
  }

  @Override
  public void commit(boolean required) throws SQLException {
    delegate.commit(required);
    tcm.commit();
  }

  @Override
  public void rollback(boolean required) throws SQLException {
    try {
      delegate.rollback(required);
    } finally {
      if (required) {
        tcm.rollback();
      }
    }
  }

  private void ensureNoOutParams(MappedStatement ms, BoundSql boundSql) {
    if (ms.getStatementType() == StatementType.CALLABLE) {
      for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
        if (parameterMapping.getMode() != ParameterMode.IN) {
          throw new ExecutorException(
              "Caching stored procedures with OUT params is not supported.  Please configure useCache=false in "
                  + ms.getId() + " statement.");
        }
      }
    }
  }

  @Override
  public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
    return delegate.createCacheKey(ms, parameterObject, rowBounds, boundSql);
  }

  @Override
  public boolean isCached(MappedStatement ms, CacheKey key) {
    return delegate.isCached(ms, key);
  }

  @Override
  public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key,
      Class<?> targetType) {
    delegate.deferLoad(ms, resultObject, property, key, targetType);
  }

  @Override
  public void clearLocalCache() {
    delegate.clearLocalCache();
  }

  private void flushCacheIfRequired(MappedStatement ms) {
    Cache cache = ms.getCache();
    if (cache != null && ms.isFlushCacheRequired()) {
      tcm.clear(cache);
    }
  }

  @Override
  public void setExecutorWrapper(Executor executor) {
    throw new UnsupportedOperationException("This method should not be called");
  }

}
