package ePubFragment;

import javax.xml.parsers.*;
import javax.xml.transform.*;
import org.w3c.dom.*;
import java.io.*;
import java.util.Properties;
import java.util.concurrent.*;
import javax.xml.transform.dom.*;
import javax.xml.transform.stream.*;
import com.mongodb.Mongo;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.BasicDBObject;
import org.apache.commons.codec.binary.Base64;
import java.util.HashMap;

public class unpack {
	static private DB db;
	static private DBCollection coll;
	
	public static DBCollection getMongoDbColl()
	{
		return coll;
	}
	
	public static void setMongoDbColl (DBCollection dbcoll)
	{
		coll = dbcoll;
	}
	
	public static DB getMongoDB ()
	{
		return db;
	}
	
	public static void setMongoDB (DB mongodb)
	{
		db = mongodb;
	}
	
	public static void main(String argv[]) {
 
	  try {
		  
		  if (argv.length < 2)
		  {
			  System.out.println("need the book folder name and config.properties location" );
			  return;
		  }
			  
		  
		  if (argv[0].isEmpty()) 
			{
				System.out.println("need the book folder name" );
				return;
			}
		  
		  if (argv[1].isEmpty()) 
			{
				System.out.println("config.properties file location" );
				return;
			}
		  
		//String dir = System.getProperty("user.dir").toString();
		// URL propertiesFile = new URL(root, "config.properties");
		String dir = argv[1].toString();
		Properties prop = new Properties();
	    prop.load (new FileInputStream (dir +"/config.properties"));
		  
		String mongohost = prop.getProperty("mongohost").toString();
		String mongodbname = prop.getProperty("dbname").toString();
		String ePubPath = prop.getProperty("ePubPath");
	    
		
		String bookFolder = argv[0];
		String ePubFilePath = ePubPath + "/"+bookFolder + "/OEBPS/"; 
		
		File fXmlFile = new File(ePubFilePath + "content.opf");
		File navFile = new File (ePubFilePath + "toc.ncx");
		File interactionFile = new File (ePubFilePath + "/interactions/interactions.xml" );
			
		DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder dBuilder = docFactory.newDocumentBuilder();
		Document doc = dBuilder.parse(fXmlFile), 
					   navDoc = dBuilder.parse(navFile);
		

		navDoc.getDocumentElement().normalize();
		doc.getDocumentElement().normalize();
		Mongo m = new Mongo( mongohost , 27017 );
		setMongoDB (m.getDB( mongodbname ));
		
		NodeList nodeList;
		Element node;
		
		nodeList = doc.getElementsByTagName("dc:title"); 
		node = (Element)nodeList.item(0);
		String title = node.getTextContent(); 
		
		nodeList = doc.getElementsByTagName("dc:creator"); 
		node = (Element)nodeList.item(0);
		String creator = node.getTextContent(); 
		
		nodeList = doc.getElementsByTagName("dc:identifier"); 
		node = (Element)nodeList.item(0);
		String bookid = node.getTextContent(); 
		
		nodeList = doc.getElementsByTagName("dc:publisher"); 
		node = (Element)nodeList.item(0);
		String publisher = node.getTextContent(); 
		
		String bookCategory = null;
		nodeList = doc.getElementsByTagName("meta"); 
		for (int i = 0; i < nodeList.getLength(); i++) {
			   
			Node nNode = nodeList.item(i);
		 
			Element e = (Element) nNode;   
			String metaName = e.getAttribute("name");	
			if (metaName.equals ("BookCategory") ){
				bookCategory = e.getAttribute("content");
			   
			}			
		}

		
		String[] parts = bookid.split(":");
		String ISBN = parts[2];
		setMongoDbColl (db.getCollection(ISBN));
		String interactionXml="NA"; 
		
	    System.out.println ("collection name= " + parts[2]);
		BasicDBObject bookMetaDoc = new BasicDBObject();
		// BasicDBObject bookMetaDocDetail = new BasicDBObject();
		bookMetaDoc.put("name", "metaHeader");
	    bookMetaDoc.put("title", title);
	    bookMetaDoc.put("bookid", bookid);
	    bookMetaDoc.put("author", creator);
	    bookMetaDoc.put("publisher", publisher);
		bookMetaDoc.put("bookCatetory", bookCategory);
	   
		// Retrieve interaction XML if any
	    if (interactionFile.exists()) 
		{
			 StringBuffer fileData = new StringBuffer(1000);
	         BufferedReader reader = new BufferedReader(
	                    new FileReader(interactionFile));
	            char[] buf = new char[1024];
	            int numRead=0;
	            while((numRead=reader.read(buf)) != -1){
	                String readData = String.valueOf(buf, 0, numRead);
	                fileData.append(readData);
	                buf = new char[1024];
	            }
	            reader.close();
	            interactionXml = fileData.toString();
		} else {
			System.out.println("no interaction file!");
		}
	    
	    bookMetaDoc.put("interaction", interactionXml);
		getMongoDbColl().insert(bookMetaDoc);
	    
		HashMap<String, String> bookIndexMap = new HashMap<String,String>();
		//System.out.println("Root element :" + doc.getDocumentElement().getNodeName());
		
		NodeList nList = doc.getElementsByTagName("item");
		for (int i = 0; i < nList.getLength(); i++) {
		   
			Node nNode = nList.item(i);

			Element e = (Element) nNode;   
			String ID = e.getAttribute("id");	
			String mediaType = e.getAttribute("media-type");
			if (mediaType.toLowerCase().contains("application/xhtml+xml")) {
				bookIndexMap.put(ID, e.getAttribute("href"));
			}
		}
 
		//ArrayList<String> playOrderTable = new ArrayList<String>();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		
		nList = doc.getElementsByTagName("itemref");
		int pageorder = 1;
		for (int i = 0; i < nList.getLength(); i++) {
		   
			Node nNode = nList.item(i);

			Element e = (Element) nNode;   
			String ID = e.getAttribute("idref");
			String refName = bookIndexMap.get(ID);
			
			BasicDBObject metaIndex = new BasicDBObject();	
			metaIndex.put("name", "contentIndex");
			metaIndex.put("navId",ID);
			metaIndex.put("playOrder", pageorder++);
		
			getMongoDbColl().insert(metaIndex);
			pool.execute (new htmlHandler(ISBN, ID, ePubFilePath, refName,getMongoDbColl()));  
		}
			
	  } 
	  catch (Exception e) {
		e.printStackTrace();
	  } 
	  
	}
}
 
class htmlHandler implements Runnable {
	
	private final String bookID, fragID, filePath, filename;
	private DBCollection coll;
	
	public synchronized DBCollection getMongoDbColl()
	{
		return coll;
	}
	
	public synchronized void setMongoDbColl (DBCollection dbcoll)
	{
		coll = dbcoll;
	}
	
	
	htmlHandler (String isbn, String id, String path, String name, DBCollection coll) {
		fragID = id;
		filePath = path;
		filename = name;
		bookID = isbn; 
		setMongoDbColl(coll);
	}
	public void run () {
		
		String[] fileNameExt = filename.split("\\.");
		String tbFileName = fileNameExt[0]+ "_tb.jpg", tbImagePath = filePath + "/tbimages/";
		String tbImgageBase64Data = null;

		try {
			File fXmlFile = new File(filePath+filename), tbFile =new File(tbImagePath+tbFileName);
			System.out.println (fXmlFile);
			System.out.println(tbFileName);
			
			if (tbFile.exists()){
				FileInputStream tbImageFile = new FileInputStream(tbFile);
				byte tbImageData[] = new byte[(int) tbFile.length()];
				tbImageFile.read(tbImageData);
	 
				// Converting Image byte array into Base64 String     
				tbImgageBase64Data = Base64.encodeBase64String (tbImageData);
			}
			
			DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
			Document doc = dBuilder.parse(fXmlFile);
			doc.getDocumentElement().normalize();
			
			NodeList htmlTagList = doc.getElementsByTagName("html");
			Element htmlNode = (Element) htmlTagList.item(0);
			htmlNode.setAttribute("oncontextmenu", "return false");
			
			NodeList headerList = doc.getElementsByTagName("head");
			Element scriptElm = doc.createElement("script");
			scriptElm.setAttribute("src", "inject.js");
			headerList.item(0).appendChild((Node) scriptElm);
		
			NodeList linkRefList = doc.getElementsByTagName("link");
			for (int i = 0; i < linkRefList.getLength(); i++) {
				Node nNode = linkRefList.item(i);
				Element e = (Element) nNode;  
				
				String linktype = e.getAttribute("rel");
				if (linktype.toLowerCase().contains("stylesheet")) 
				{
					String link = e.getAttribute("href");
					String newlink = bookID + "/" + link;
					System.out.println(newlink);
					e.setAttribute("href", newlink);
				}
			}
			
			NodeList imgList = doc.getElementsByTagName("img");
			for (int i = 0; i < imgList.getLength(); i++) { 
				   Node nNode = imgList.item(i);
				   Element e = (Element) nNode;  
				   String imgName = e.getAttribute("src");
				   String mediaType = "image/";
				   if (imgName.contains("jpg"))
				        mediaType += "jpg";
				   else if (imgName.contains("png"))
					   	mediaType += "jpg";
				   else if (imgName.contains("gif")) 
					    mediaType += "gif";
				   
				   //encode image to base64 code and attach to the HTML document
				   // Scanner sc = new Scanner(new File (filePath + "/" +  imgName));
			       String imageFile = filePath + imgName; 
			       System.out.println ("image source = " + imageFile);
				   File file = new File(imageFile);
				   FileInputStream imageInFile = new FileInputStream(file);
			       
		            byte imageData[] = new byte[(int) file.length()];
		            imageInFile.read(imageData);
		 
		            // Converting Image byte array into Base64 String     
				   String base64Data = Base64.encodeBase64String (imageData);
				   e.setAttribute ("src", "data:"+ mediaType+ ";base64," + base64Data);	
				}
			
		       DOMSource domSource = new DOMSource(doc);
		       
		       StringWriter writer = new StringWriter();
		       StreamResult result = new StreamResult(writer);
		       TransformerFactory tf = TransformerFactory.newInstance();
		       Transformer transformer = tf.newTransformer();
		       transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
		       transformer.transform(domSource, result);
		       
		       BasicDBObject fragmentdoc = new BasicDBObject();
		       fragmentdoc.put("name", "fragment");
		       fragmentdoc.put("fragId", fragID);
		       fragmentdoc.put("frags", writer.toString());
		       fragmentdoc.put("tb",tbImgageBase64Data);
		       getMongoDbColl().insert(fragmentdoc);
		     
		       //System.out.println (writer.toString());
		}
		
		catch (Exception docE) { 
			docE.printStackTrace();
		}
	}
}
