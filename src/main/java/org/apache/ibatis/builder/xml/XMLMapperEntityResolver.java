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
package org.apache.ibatis.builder.xml;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import org.apache.ibatis.io.Resources;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Offline entity resolver for the MyBatis DTDs.
 * entityResolver比较好理解，跟Spring的XML标签解析器一样，有默认的解析器，也有自定义的比如tx，dubbo等，
 * 主要使用了策略模式，在这里mybatis硬编码为了XMLMapperEntityResolver。
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class XMLMapperEntityResolver implements EntityResolver {

  private static final String IBATIS_CONFIG_SYSTEM = "ibatis-3-config.dtd";
  private static final String IBATIS_MAPPER_SYSTEM = "ibatis-3-mapper.dtd";
  private static final String MYBATIS_CONFIG_SYSTEM = "mybatis-3-config.dtd";
  private static final String MYBATIS_MAPPER_SYSTEM = "mybatis-3-mapper.dtd";

  private static final String MYBATIS_CONFIG_DTD = "org/apache/ibatis/builder/xml/mybatis-3-config.dtd";
  private static final String MYBATIS_MAPPER_DTD = "org/apache/ibatis/builder/xml/mybatis-3-mapper.dtd";

  /**
   * Converts a public DTD into a local one.
   *  将公共的DTD转换为本地模式
   *
   *  mybatis解析的时候，引用了本地的DTD文件，和本类在同一个package下，
   *  其中的ibatis-3-config.dtd应该主要是用于兼容用途。
   *  在其中getInputSource(MYBATIS_CONFIG_DTD, publicId, systemId)的调用里面有两个参数
   *  publicId（公共标识符）和systemId（系统标示符），他们是XML 1.0规范的一部分。他们的使用如下：
   *
   *    <?xml version="1.0"?>
   *    <!DOCTYPE greeting SYSTEM "hello.dtd">
   *    <greeting>Hello, world!</greeting>
   *
   *  系统标识符 “hello.dtd” 给出了此文件的 DTD 的地址（一个 URI 引用）。
   *  在mybatis中，这里指定的是mybatis-3-config.dtd和ibatis-3-config.dtd。
   *  publicId则一般从XML文档的DOCTYPE中获取，如下：
   *
   *    <!DOCTYPE configuration PUBLIC "-//mybatis.org//DTD Config 3.0//EN"
   *     "http://mybatis.org/dtd/mybatis-3-config.dtd">
   *
   * @param publicId
   *          The public id that is what comes after "PUBLIC"
   * @param systemId
   *          The system id that is what comes after the public id.
   *
   * @return The InputSource for the DTD
   *
   * @throws org.xml.sax.SAXException
   *           If anything goes wrong
   */
  @Override
  public InputSource resolveEntity(String publicId, String systemId) throws SAXException {
    try {
      if (systemId != null) {
        String lowerCaseSystemId = systemId.toLowerCase(Locale.ENGLISH);
        if (lowerCaseSystemId.contains(MYBATIS_CONFIG_SYSTEM) || lowerCaseSystemId.contains(IBATIS_CONFIG_SYSTEM)) {
          return getInputSource(MYBATIS_CONFIG_DTD, publicId, systemId);
        }
        if (lowerCaseSystemId.contains(MYBATIS_MAPPER_SYSTEM) || lowerCaseSystemId.contains(IBATIS_MAPPER_SYSTEM)) {
          return getInputSource(MYBATIS_MAPPER_DTD, publicId, systemId);
        }
      }
      return null;
    } catch (Exception e) {
      throw new SAXException(e.toString());
    }
  }

  private InputSource getInputSource(String path, String publicId, String systemId) {
    InputSource source = null;
    if (path != null) {
      try {
        InputStream in = Resources.getResourceAsStream(path);
        source = new InputSource(in);
        source.setPublicId(publicId);
        source.setSystemId(systemId);
      } catch (IOException e) {
        // ignore, null is ok
      }
    }
    return source;
  }

}
