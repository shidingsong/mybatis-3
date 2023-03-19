package org.apache.ibatis.xpath;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.FileInputStream;

/**
 *  1.节点类型
 *     XPath中有七种结点类型：元素、属性、文本、命名空间、处理指令、注释以及文档节点（或称为根节点）。
 *     文档中存在元素节点，属性节点，根节点
 *
 *  2.常用路径表达式
 *        表达式                                   描述
 *     节点名称(nodename)	                    选取此节点的所有子节点
 *          /	                                   从根节点选取
 *         //	                          从匹配选择的当前节点选择文档中的节点，而不考虑它们的位置
 *         .	                                  选取当前节点
 *         ..	                                  选取当前节点的父节点
 *          @	                                  选取属性
 *
 *    示例如下： //@lang 选取所有名为 lang 的属性
 *
 *  3.限定语
 *  用来查找某个特定的节点或者包含某个指定的值的节点。以方括号括起
 * //book[price>35.00]	选择所有book 元素，且其中的 price 元素的值须大于 35.00
 * /bookstore/book[1]	选取属于 bookstore 子元素的第一个 book 元素。
 * /bookstore/book[last()]	选取属于 bookstore 子元素的最后一个 book 元素。
 * /bookstore/book[last()-1]	选取属于 bookstore 子元素的倒数第二个 book 元素。
 * /bookstore/book[position()<3]	选取最前面的两个属于 bookstore 元素的子元素的 book 元素。
 * //title[@lang]	选取所有拥有名为 lang 的属性的 title 元素。
 * //title[@lang='eng']	选取所有 title 元素，且这些元素拥有值为 eng 的 lang 属性。
 * /bookstore/book[price>35.00]	选取所有 bookstore 元素的 book 元素，且其中的 price 元素的值须大于 35.00。
 * /bookstore/book[price>35.00]/title	选取所有 bookstore 元素中的 book 元素的 title 元素，且其中的 price 元素的值须大于 35.00。
 *
 * 4 .通配符
 *         通配符	                         描述
 *           *	                        匹配任何元素节点
 *          @*                          匹配任何属性节点
 *        node()	                          匹配任何类型的节点
 *         |	                          选取若干路径
 *  使用示例
 *        路径表达式	                         结果
 *        /bookstore/*	                选取 bookstore 元素的所有子节点
 *          //*	                          选取文档中的所有元素
 *        //title[@*]	                     选取所有带有属性的 title 元素。
 *      //book/title | //book/price	       选取所有 book 元素的 tilte 和 price 元素。
 *         //title | //price	          选取所有文档中的 title 和 price 元素。
 *      /bookstore/book/title | //price	选取所有属于 bookstore 元素的 book 元素的 title 元素，以及文档中所有的 price 元素
 *
 *
 */
public class XPathDemo {
  private static Document doc;
  private static XPath xpath;

  public static void main(String[] args) throws Exception {
    init();
    getRootEle();
    getChildEles();
    getPartEles();
    haveChildsEles();
    getLevelEles();
    getAttrEles();

    //打印根节点下的所有元素节点
    System.out.println(doc.getDocumentElement().getChildNodes().getLength());
    NodeList nodeList = doc.getDocumentElement().getChildNodes();
    for (int i = 0; i < nodeList.getLength(); i++) {
      if (nodeList.item(i).getNodeType() == Node.ELEMENT_NODE) {
        System.out.print(nodeList.item(i).getNodeName() + " ");
      }
    }
  }

  // 初始化Document、XPath对象
  public static void init() throws Exception {
    // 创建Document对象
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    dbf.setValidating(false);
    DocumentBuilder db = dbf.newDocumentBuilder();
    doc = db.parse(new FileInputStream(new File("D:\\learn\\mybatis-3\\src\\test\\resources\\org\\apache\\ibatis\\xpath\\demo.xml")));

    // 创建XPath对象
    XPathFactory factory = XPathFactory.newInstance();
    xpath = factory.newXPath();
  }

  // 获取根元素
  // 表达式可以更换为/*,/rss
  public static void getRootEle() throws XPathExpressionException {
    System.out.println("-------------------getRootEle--------------------------------");
    Node node = (Node) xpath.evaluate("/rss", doc, XPathConstants.NODE);
    System.out.println(node.getNodeName() + "--------"
      + node.getNodeValue());
  }

  // 获取子元素并打印
  public static void getChildEles() throws XPathExpressionException {
    System.out.println("-------------------getChildEles--------------------------------");
    NodeList nodeList = (NodeList) xpath.evaluate("/rss/channel/*", doc,
      XPathConstants.NODESET);
    for (int i = 0; i < nodeList.getLength(); i++) {
      System.out.print(nodeList.item(i).getNodeName() + " ");
    }
    System.out.println();
  }

  // 获取部分元素
  // 只获取元素名称为title的元素
  public static void getPartEles() throws XPathExpressionException {
    System.out.println("-------------------getPartEles--------------------------------");
    NodeList nodeList = (NodeList) xpath.evaluate("//*[name() = 'title']",
      doc, XPathConstants.NODESET);
    for (int i = 0; i < nodeList.getLength(); i++) {
      System.out.println(nodeList.item(i).getNodeName() + "-->"
        + nodeList.item(i).getTextContent());
    }
    System.out.println();
  }

  // 获取包含子节点的元素
  public static void haveChildsEles() throws XPathExpressionException {
    System.out.println("-------------------haveChildsEles--------------------------------");
    NodeList nodeList = (NodeList) xpath.evaluate("//*[*]", doc,
      XPathConstants.NODESET);
    for (int i = 0; i < nodeList.getLength(); i++) {
      System.out.println(nodeList.item(i).getNodeName() + " ");
    }
    System.out.println();
  }

  // 获取指定层级的元素
  public static void getLevelEles() throws XPathExpressionException {
    System.out.println("-------------------getLevelEles--------------------------------");
    NodeList nodeList = (NodeList) xpath.evaluate("/*/*/*/*", doc,
      XPathConstants.NODESET);
    for (int i = 0; i < nodeList.getLength(); i++) {
      System.out.println(nodeList.item(i).getNodeName() + "-->"
        + nodeList.item(i).getTextContent() + " ");
    }
    System.out.println("-----------------------------");
  }

  // 获取指定属性的元素
  // 获取所有大于指定价格的书箱
  public static void getAttrEles() throws XPathExpressionException {
    System.out.println("-------------------getAttrEles--------------------------------");
    NodeList nodeList = (NodeList) xpath.evaluate("//bookstore/book[price>35.00]/title", doc,
      XPathConstants.NODESET);
    for (int i = 0; i < nodeList.getLength(); i++) {
      System.out.println(nodeList.item(i).getNodeName() + "-->"
        + nodeList.item(i).getTextContent() + " ");
    }
    System.out.println();
  }
}
