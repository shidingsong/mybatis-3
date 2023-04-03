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
package org.apache.ibatis.reflection;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.ReflectPermission;
import java.lang.reflect.Type;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ibatis.reflection.invoker.AmbiguousMethodInvoker;
import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;
import org.apache.ibatis.util.MapUtil;

/**
 * This class represents a cached set of class definition information that allows for easy mapping between property
 * names and getter/setter methods.
 *
 * @author Clinton Begin
 */
public class Reflector {

  // 该方法通过判断 Class类中 isRecord方法存在与否判断 jdk版本
  // true, Class.isRecord()存在，jdk version > 14, false, jdk version < 14
  private static final MethodHandle isRecordMethodHandle = getIsRecordMethodHandle();
  private final Class<?> type;
  private final String[] readablePropertyNames; // getXXX()、isXXX()方法中的XXX属性名
  private final String[] writablePropertyNames; // setXX()方法中的XXX属性名
  private final Map<String, Invoker> setMethods = new HashMap<>();
  private final Map<String, Invoker> getMethods = new HashMap<>();
  private final Map<String, Class<?>> setTypes = new HashMap<>();// (属性名,入参类型)
  private final Map<String, Class<?>> getTypes = new HashMap<>();// (属性名,出参类型)
  private Constructor<?> defaultConstructor;// class类中无参构造器

  /**
   * 类 get、set方法对应属性容器 <大写属性名, 真实属性名>
   */
  private final Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

  public Reflector(Class<?> clazz) {
    type = clazz;
    addDefaultConstructor(clazz);
    // 解析class类及父类中所有方法，包括抽象类中接口方法
    Method[] classMethods = getClassMethods(clazz);
    if (isRecord(type)) {// 是否使用了 record语法糖
      addRecordGetMethods(classMethods);
    } else {
      addGetMethods(classMethods);
      addSetMethods(classMethods);
      addFields(clazz);
    }
    readablePropertyNames = getMethods.keySet().toArray(new String[0]);
    writablePropertyNames = setMethods.keySet().toArray(new String[0]);
    for (String propName : readablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
    for (String propName : writablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
  }

  private void addRecordGetMethods(Method[] methods) {
    Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 0)
        .forEach(m -> addGetMethod(m.getName(), m, false));
  }

  // 寻找class类中无参构造器，初始化defaultConstructor
  private void addDefaultConstructor(Class<?> clazz) {
    Constructor<?>[] constructors = clazz.getDeclaredConstructors();
    Arrays.stream(constructors).filter(constructor -> constructor.getParameterTypes().length == 0).findAny()
        .ifPresent(constructor -> this.defaultConstructor = constructor);
  }

  //
  private void addGetMethods(Method[] methods) {
    Map<String, List<Method>> conflictingGetters = new HashMap<>();// (属性名,List<Method>)
    // 遍历methods 过滤出get方法 添加到 conflictingGetters
    Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 0 && PropertyNamer.isGetter(m.getName()))
        .forEach(m -> addMethodConflict(conflictingGetters, PropertyNamer.methodToProperty(m.getName()), m));
    resolveGetterConflicts(conflictingGetters);
  }

  /**
   * 父子继承关系测试
   *
   * class A{
   * }
   * class B extends A{
   * }
   * class C extends B{
   * }
   * public class test {
   *     public static void main(String[] args) {
   *         A a = new A();
   *         B b = new B();
   *         B b1 = new B();
   *         C c = new C();
   *         System.out.println(a.getClass().isAssignableFrom(a.getClass())); // true
   *         System.out.println(a.getClass().isAssignableFrom(b.getClass())); // true
   *         System.out.println(a.getClass().isAssignableFrom(c.getClass())); // true
   *         System.out.println(b1.getClass().isAssignableFrom(b.getClass())); // true
   *
   *         System.out.println(b.getClass().isAssignableFrom(c.getClass())); // true
   *
   *         System.out.println("=====================================");
   *         System.out.println(A.class.isAssignableFrom(a.getClass())); // true
   *         System.out.println(A.class.isAssignableFrom(b.getClass())); // true
   *         System.out.println(A.class.isAssignableFrom(c.getClass())); // true
   *
   *         System.out.println("=====================================");
   *         System.out.println(Object.class.isAssignableFrom(a.getClass())); // true
   *         System.out.println(Object.class.isAssignableFrom(String.class)); // true
   *         System.out.println(String.class.isAssignableFrom(Object.class)); // false
   *     }
   * }
   *
   * 接口的实现关系测试
   *
   * interface InterfaceA{
   * }
   *
   * class ClassB implements InterfaceA{
   *
   * }
   * class ClassC implements InterfaceA{
   *
   * }
   * class ClassD extends ClassB{
   *
   * }
   * public class InterfaceTest {
   *     public static void main(String[] args) {
   *         System.out.println(InterfaceA.class.isAssignableFrom(InterfaceA.class)); // true
   *         System.out.println(InterfaceA.class.isAssignableFrom(ClassB.class)); // true
   *         System.out.println(InterfaceA.class.isAssignableFrom(ClassC.class)); // true
   *         System.out.println(ClassB.class.isAssignableFrom(ClassC.class)); // false
   *         System.out.println("============================================");
   *
   *         System.out.println(ClassB.class.isAssignableFrom(ClassD.class)); // true
   *         System.out.println(InterfaceA.class.isAssignableFrom(ClassD.class)); // true
   *     }
   * }
   *
   * isAssignableFrom是用来判断子类和父类的关系的，或者接口的实现类和接口的关系的，
   * 默认所有的类的终极父类都是Object。
   * 如果A.isAssignableFrom(B)结果是true，证明B可以转换成为A,也就是A可以由B转换而来。
   * @param conflictingGetters conflictingGetters
   */
  private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
    for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
      Method winner = null;
      String propName = entry.getKey();
      boolean isAmbiguous = false;
      for (Method candidate : entry.getValue()) {
        if (winner == null) {
          winner = candidate;
          continue;
        }
        Class<?> winnerType = winner.getReturnType();
        Class<?> candidateType = candidate.getReturnType();
        if (candidateType.equals(winnerType)) {
          if (!boolean.class.equals(candidateType)) {
            isAmbiguous = true;// get方法无参且返回值类型相同非Boolean，则方法重写出错
            break;
          }
          if (candidate.getName().startsWith("is")) {
            winner = candidate;
          }
        } else if (candidateType.isAssignableFrom(winnerType)) {// winnerType能转成 candidateType 即winnerType继承或实现candidateType
          // OK getter type is descendant
        } else if (winnerType.isAssignableFrom(candidateType)) {// candidateType能转成 winnerType
          winner = candidate;
        } else {
          isAmbiguous = true;
          break;
        }
      }
      addGetMethod(propName, winner, isAmbiguous);
    }
  }

  /**
   * get方法 封装成 MethodInvoker反射对象 添加到 getMethods，及返回值类型添加到 getTypes
   * @param name 属性
   * @param method method
   * @param isAmbiguous 方法返回类型是否有 歧义
   */
  private void addGetMethod(String name, Method method, boolean isAmbiguous) {
    MethodInvoker invoker = isAmbiguous ? new AmbiguousMethodInvoker(method, MessageFormat.format(
        "Illegal overloaded getter method with ambiguous type for property ''{0}'' in class ''{1}''. This breaks the JavaBeans specification and can cause unpredictable results.",
        name, method.getDeclaringClass().getName())) : new MethodInvoker(method);
    getMethods.put(name, invoker);
    Type returnType = TypeParameterResolver.resolveReturnType(method, type);
    getTypes.put(name, typeToClass(returnType));
  }

  private void addSetMethods(Method[] methods) {
    Map<String, List<Method>> conflictingSetters = new HashMap<>();
    Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 1 && PropertyNamer.isSetter(m.getName()))
        .forEach(m -> addMethodConflict(conflictingSetters, PropertyNamer.methodToProperty(m.getName()), m));
    resolveSetterConflicts(conflictingSetters);
  }

  /**
   * 属性对应method添加到  map容器
   * @param conflictingMethods  map容器
   * @param name  属性
   * @param method 属性对应method
   */
  private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
    if (isValidPropertyName(name)) {
      List<Method> list = MapUtil.computeIfAbsent(conflictingMethods, name, k -> new ArrayList<>());
      list.add(method);
    }
  }

  private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
    for (Entry<String, List<Method>> entry : conflictingSetters.entrySet()) {
      String propName = entry.getKey();
      List<Method> setters = entry.getValue();
      Class<?> getterType = getTypes.get(propName);
      // 属性对应get方法是否有歧义
      boolean isGetterAmbiguous = getMethods.get(propName) instanceof AmbiguousMethodInvoker;
      boolean isSetterAmbiguous = false;
      Method match = null;
      for (Method setter : setters) {
        // 属性对应get方法是无歧义 且set方法入参类型 与 get方法返回值类型一致
        if (!isGetterAmbiguous && setter.getParameterTypes()[0].equals(getterType)) {
          // should be the best match
          match = setter;
          break;
        }
        if (!isSetterAmbiguous) {
          match = pickBetterSetter(match, setter, propName);
          isSetterAmbiguous = match == null;
        }
      }
      if (match != null) {
        addSetMethod(propName, match);
      }
    }
  }

  private Method pickBetterSetter(Method setter1, Method setter2, String property) {
    if (setter1 == null) {
      return setter2;
    }
    Class<?> paramType1 = setter1.getParameterTypes()[0];
    Class<?> paramType2 = setter2.getParameterTypes()[0];
    if (paramType1.isAssignableFrom(paramType2)) {
      return setter2;
    }
    if (paramType2.isAssignableFrom(paramType1)) {
      return setter1;
    }
    MethodInvoker invoker = new AmbiguousMethodInvoker(setter1,
        MessageFormat.format(
            "Ambiguous setters defined for property ''{0}'' in class ''{1}'' with types ''{2}'' and ''{3}''.", property,
            setter2.getDeclaringClass().getName(), paramType1.getName(), paramType2.getName()));
    setMethods.put(property, invoker);
    Type[] paramTypes = TypeParameterResolver.resolveParamTypes(setter1, type);
    setTypes.put(property, typeToClass(paramTypes[0]));
    return null;
  }

  private void addSetMethod(String name, Method method) {
    MethodInvoker invoker = new MethodInvoker(method);
    setMethods.put(name, invoker);
    Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
    setTypes.put(name, typeToClass(paramTypes[0]));
  }

  private Class<?> typeToClass(Type src) {
    Class<?> result = null;
    if (src instanceof Class) {
      result = (Class<?>) src;
    } else if (src instanceof ParameterizedType) {
      result = (Class<?>) ((ParameterizedType) src).getRawType();
    } else if (src instanceof GenericArrayType) {
      Type componentType = ((GenericArrayType) src).getGenericComponentType();
      if (componentType instanceof Class) {
        result = Array.newInstance((Class<?>) componentType, 0).getClass();
      } else {
        Class<?> componentClass = typeToClass(componentType);
        result = Array.newInstance(componentClass, 0).getClass();
      }
    }
    if (result == null) {
      result = Object.class;
    }
    return result;
  }

  private void addFields(Class<?> clazz) {
    Field[] fields = clazz.getDeclaredFields();
    for (Field field : fields) {
      if (!setMethods.containsKey(field.getName())) {
        // issue #379 - removed the check for final because JDK 1.5 allows
        // modification of final fields through reflection (JSR-133). (JGB)
        // pr #16 - final static can only be set by the classloader
        int modifiers = field.getModifiers();
        if ((!Modifier.isFinal(modifiers) || !Modifier.isStatic(modifiers))) {
          addSetField(field);
        }
      }
      if (!getMethods.containsKey(field.getName())) {
        addGetField(field);
      }
    }
    if (clazz.getSuperclass() != null) {
      addFields(clazz.getSuperclass());
    }
  }

  private void addSetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      setMethods.put(field.getName(), new SetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      setTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private void addGetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      getMethods.put(field.getName(), new GetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      getTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private boolean isValidPropertyName(String name) {
    return (!name.startsWith("$") && !"serialVersionUID".equals(name) && !"class".equals(name));
  }

  /**
   * 该方法返回一个数组，数组中包含class类及父类中所有声明的方法对象Method
   *
   * This method returns an array containing all methods declared in this class and any superclass. We use this method,
   * instead of the simpler <code>Class.getMethods()</code>, because we want to look for private methods as well.
   *
   * @param clazz
   *          The class
   *
   * @return An array containing all methods in this class
   */
  private Method[] getClassMethods(Class<?> clazz) {
    // uniqueMethods key = 方法签名
    //   方法签名 = 无入参方法返回 Class名#方法名，
    //             入参方法返回 Class名#方法名:入参1类型Class名,入参2类型Class名,...
    //       public void method(String s) {
    //    　　　 System.out.println(s);
    //    　  }
    //     方法签名 = Void#method:String
    Map<String, Method> uniqueMethods = new HashMap<>();
    Class<?> currentClass = clazz;
    // getDeclaredMethod*()获取的是类自身声明的所有方法，包含public、protected和private方法。
    // getMethod*()获取的是类的所有共有方法，包括自身的所有public方法，和从基类继承的、从接口实现的所有public方法。
    while (currentClass != null && currentClass != Object.class) {
      addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

      // we also need to look for interface methods -
      // because the class may be abstract
      Class<?>[] interfaces = currentClass.getInterfaces();//抽象类
      for (Class<?> anInterface : interfaces) {
        addUniqueMethods(uniqueMethods, anInterface.getMethods());
      }

      currentClass = currentClass.getSuperclass();
    }

    Collection<Method> methods = uniqueMethods.values();

    return methods.toArray(new Method[0]);
  }

  /**
   *  currentMethod.isBridge()是 Java反射中method.isBridge() 桥接方法
   *
   *  桥接方法是 JDK 1.5 引入泛型后，为了使Java的泛型方法生成的字节码和 1.5 版本前的字节码相兼容，
   *  由编译器自动生成的方法。我们可以通过Method.isBridge()方法来判断一个方法是否是桥接方法。
   *  假定接口
   *     public interface SuperClass<T> {
   *       void method(T t);
   *     }
   *  实现类
   *     public class AClass implements SuperClass<String> {
   *       @Override
   *       public void method(String s) {
   * 　　　　 System.out.println(s);
   * 　　  }
   *     }
   *  因为泛型是在1.5引入的，为了向前兼容，所以会在编译时去掉泛型（泛型擦除）。
   *  那么SuperClass接口中的method方法的参数在虚拟机中只能是Object。
   *  public interface SuperClass {
   *     void method(Object  t);
   *  }
   *  而 AClass 实现了SuperClass 接口，但是它的实现方法却是：
   *  public void method(String s) {
   * 　　System.out.println(s);
   *  }
   *  根本就没有实现 void method(Object t) 方法。 这怎么回事，其实虚拟机自动实现了一个方法。
   *
   *  AClass在虚拟机中是这个样子：
   *  public class AClass implements SuperClass  {
   *     public void method(String s) {  // 非桥接方法
   *         System.out.println(s);
   *     }
   *     public void method(Object s) {  // 桥接方法
   *          this.method((String) s);
   *     }
   *  }
   *  这个void method(Object s)  就是桥接方法。
   *
   * @param uniqueMethods
   * @param methods
   */
  private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
    for (Method currentMethod : methods) {
      if (!currentMethod.isBridge()) {// 排除桥接方法
        String signature = getSignature(currentMethod);
        // check to see if the method is already known
        // if it is known, then an extended class must have
        // overridden a method
        if (!uniqueMethods.containsKey(signature)) {
          uniqueMethods.put(signature, currentMethod);
        }
      }
    }
  }

  /**
   * 方法签名 = 无入参方法返回 Class名#方法名，
   *            入参方法返回 Class名#方法名:入参1类型Class名,入参2类型Class名,...
   * 如：    public void method(String s) {
   *    　　　 System.out.println(s);
   *    　  }
   *  方法签名 = Void#method:String
   *
   * @param method
   * @return
   */
  private String getSignature(Method method) {
    StringBuilder sb = new StringBuilder();
    Class<?> returnType = method.getReturnType();
    if (returnType != null) {
      sb.append(returnType.getName()).append('#');
    }
    sb.append(method.getName());
    Class<?>[] parameters = method.getParameterTypes();
    for (int i = 0; i < parameters.length; i++) {
      sb.append(i == 0 ? ':' : ',').append(parameters[i].getName());
    }
    return sb.toString();
  }

  /**
   * Checks whether can control member accessible.
   *
   * @return If can control member accessible, it return {@literal true}
   *
   * @since 3.5.0
   */
  public static boolean canControlMemberAccessible() {
    try {
      SecurityManager securityManager = System.getSecurityManager();
      if (null != securityManager) {
        securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
      }
    } catch (SecurityException e) {
      return false;
    }
    return true;
  }

  /**
   * Gets the name of the class the instance provides information for.
   *
   * @return The class name
   */
  public Class<?> getType() {
    return type;
  }

  public Constructor<?> getDefaultConstructor() {
    if (defaultConstructor != null) {
      return defaultConstructor;
    }
    throw new ReflectionException("There is no default constructor for " + type);
  }

  public boolean hasDefaultConstructor() {
    return defaultConstructor != null;
  }

  public Invoker getSetInvoker(String propertyName) {
    Invoker method = setMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  public Invoker getGetInvoker(String propertyName) {
    Invoker method = getMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  /**
   * Gets the type for a property setter.
   *
   * @param propertyName
   *          - the name of the property
   *
   * @return The Class of the property setter
   */
  public Class<?> getSetterType(String propertyName) {
    Class<?> clazz = setTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets the type for a property getter.
   *
   * @param propertyName
   *          - the name of the property
   *
   * @return The Class of the property getter
   */
  public Class<?> getGetterType(String propertyName) {
    Class<?> clazz = getTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets an array of the readable properties for an object.
   *
   * @return The array
   */
  public String[] getGetablePropertyNames() {
    return readablePropertyNames;
  }

  /**
   * Gets an array of the writable properties for an object.
   *
   * @return The array
   */
  public String[] getSetablePropertyNames() {
    return writablePropertyNames;
  }

  /**
   * Check to see if a class has a writable property by name.
   *
   * @param propertyName
   *          - the name of the property to check
   *
   * @return True if the object has a writable property by the name
   */
  public boolean hasSetter(String propertyName) {
    return setMethods.containsKey(propertyName);
  }

  /**
   * Check to see if a class has a readable property by name.
   *
   * @param propertyName
   *          - the name of the property to check
   *
   * @return True if the object has a readable property by the name
   */
  public boolean hasGetter(String propertyName) {
    return getMethods.containsKey(propertyName);
  }

  public String findPropertyName(String name) {
    return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
  }

  /**
   * Class.isRecord() alternative for Java 15 and older.
   * record语法糖， java14才出来的record，类似于enum，定义了一种特殊的类。用于标记不可变的数据类。
   *
   * 正常写法
   * 定义一个用户类，一般会这么定义
   * public class User {
   *     private String name = null;
   *     private String password = null;
   *
   *     public User(String name, String password) {
   *         this.name = name;
   *         this.password = password;
   *     }
   *
   *     public String getName() {
   *         return name;
   *     }
   *
   *     public String getPassword() {
   *         return password;
   *     }
   *
   *     @Override
   *     public boolean equals(Object o) {
   *         //用于判断是否相等
   *         if (this == o) return true;
   *         if (!(o instanceof User)) return false;
   *         User user = (User) o;
   *         return name.equals(user.name) && password.equals(user.password);
   *     }
   *
   *     @Override
   *     public int hashCode() {
   *         //hash算法
   *         return Objects.hash(name, password);
   *     }
   *
   *     @Override
   *     public String toString() {
   *         return "user{" +
   *                 "name='" + name + '\'' +
   *                 ", password='" + password + '\'' +
   *                 '}';
   *     }
   * }
   *
   * 写个程序验证一下
   * public class test {
   *     public static void main(String[] args) {
   *         User1 user = new User1("XiaoMing", "123456");
   *         User1 user2 = new User1("XiaoMing", "123456");
   *
   *         System.out.println(user.name());
   *         System.out.println(user.password());
   *         System.out.println(user);
   *         System.out.println(user.equals(user2));
   *     }
   * }
   *
   * 使用record 完成与上面相同的类，使用record，需要怎么写呢？ 只用一行：
   *
   * public record User1(String name,String password) {}
   *
   * 写个程序测试一下：
   * public class test {
   *     public static void main(String[] args) {
   *         User1 user = new User1("XiaoMing", "123456");
   *
   *         System.out.println(user.name());
   *         System.out.println(user.password());
   *         System.out.println(user);
   *     }
   * }
   *
   * 分析：
   *    1、getName方法变成了name，但是功能一样的，只是命名方式变了
   *    2、因为是不可变数据类型，没有set方法
   *    3、自动toString，虽然最后结果不一样，但是可以正常看，不是com.czcode.customer.User@b66c70b0这种玩意了
   *    4、自动实现equals，如果不覆盖这个方法，两个对象比较时候会比较指向的对象是不是同一个对象，这个只是比了里面的值是否相等。
   * 总结：这种类其实就是帮你写好了样例代码
   *
   * 继续进阶一下  record中可以覆盖构造方法、创建静态方法、定义自己的方法
   * public record User1(String name, String password) {
   *     //再定义一个构造方法
   *     public User1(String name) {
   *         this(name, null);
   *     }
   *
   *     //额外定义的方法
   *     public String nameToUppercase() {
   *         return this.name.toUpperCase();
   *     }
   *
   *     //静态方法
   *     public static String nameAddPassword(User1 user1) {
   *         return user1.name + user1.password;
   *     }
   * }
   * 测试代码
   *
   * public class test {
   *     public static void main(String[] args) {
   *         User1 user = new User1("XiaoMing", "123456");
   *
   *         System.out.println(user.nameToUppercase());
   *         System.out.println(User1.nameAddPassword(user));
   *
   *         User1 userAnotherConstructor = new User1("hello");
   *         System.out.println(userAnotherConstructor);
   *     }
   * }
   *
   * 总结：这个关键字其实就相当于替你创建了一些样例代码（toSting，构造方法等），同时给你增加了一定限制条件（无法设置某个属性的值）。
   */
  private static boolean isRecord(Class<?> clazz) {
    try {
      return isRecordMethodHandle != null && (boolean) isRecordMethodHandle.invokeExact(clazz);
    } catch (Throwable e) {
      throw new ReflectionException("Failed to invoke 'Class.isRecord()'.", e);
    }
  }

  /**
   * MethodHandle 方法句柄  可以将其看作是反射的另一种方式
   * 该方法通过判断 Class类中 isRecord方法存在与否判断 jdk版本
   * @return true, Class.isRecord()存在，jdk version > 14, false, jdk version < 14
   */
  private static MethodHandle getIsRecordMethodHandle() {
    MethodHandles.Lookup lookup = MethodHandles.lookup();
    //MethodType表示一个方法类型的对象，每个MethodHandle都有一个MethodType实例，
    // MethodType用来指明方法的返回类型和参数类型。
    // 这里得到了一个方法的返回类型为boolean的MethodType。
    MethodType mt = MethodType.methodType(boolean.class);
    try {
      return lookup.findVirtual(Class.class, "isRecord", mt);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      return null;
    }
  }
}
