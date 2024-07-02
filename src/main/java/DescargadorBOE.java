import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.mongodb.client.*;
import org.bson.Document;
import org.neo4j.driver.Record;
import org.neo4j.driver.*;
import org.neo4j.driver.types.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;

import static org.neo4j.driver.Values.parameters;

public class DescargadorBOE {
    private static final String MONGO_DB_URL = "mongodb://localhost:27017";
    private static final String MONGO_DB_NAME = "boe";
    private static final String MONGO_COLLECTION_NAME = "publicaciones";

    private static final String NEO4J_URL = "bolt://localhost:7687";
    private static final String NEO4J_USER = "neo4j";
    private static final String NEO4J_PASSWORD = "password";

    // MÉTODO PARA DESCARGAR EL BOE DEL DÍA
    public void descargarBOE() {
        Calendar calendario = GregorianCalendar.getInstance();
        Date fechaActual = calendario.getTime();
        List<String> listaUrls = SacarUrl(fechaActual);
        for (String lista : listaUrls) {
            // URL del BOE
            String urlBOE = "https://www.boe.es/" + lista;
            // Establecer conexión HTTP
            URL url;
            HttpURLConnection conexion;
            try {
                url = new URL(urlBOE);
                conexion = (HttpURLConnection) url.openConnection();
                conexion.setRequestMethod("GET");
                conexion.setRequestProperty("Accept", "application/xml");
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            // Descargar contenido XML
            InputStream inputStream;
            byte[] bytes;
            int bytesLeidos;
            StringBuilder contenidoXML = new StringBuilder();
            try {
                inputStream = conexion.getInputStream();
                bytes = new byte[4096];
                while ((bytesLeidos = inputStream.read(bytes)) != -1) {
                    contenidoXML.append(new String(bytes, 0, bytesLeidos));
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                almacenar(contenidoXML);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // MÉTODO PARA OBTENER LA FECHA DEL DÍA EN FORMATO YYYYMMDD
    protected String fechaActual(Date fecha) {
        SimpleDateFormat formato = new SimpleDateFormat("yyyyMMdd");
        return formato.format(fecha);
    }

    protected void almacenar(StringBuilder archivoXML) throws IOException {
        String jsonString = transformador(archivoXML);

        // Pasar el JSON al contenedor de DOCKER MONGODB
        boolean duplicado = almacenarEnMongoDB(jsonString);

        // Pasar el MONGODB a NEO4J
        if (!duplicado) {
            almacenarEnNeo4j(jsonString);
        }
    }

    // MÉTODO PARA TRANSFORMAR EL BOE EN XML A JSON
    protected String transformador(StringBuilder archivoXML) throws IOException {
        // Inicializar ObjectMapper para XML y JSON
        XmlMapper xmlMapper = new XmlMapper();
        ObjectMapper jsonMapper = new ObjectMapper();

        // Leer el archivo XML y convertirlo a un ReadTree de JSON
        JsonNode rootNode = xmlMapper.readTree(String.valueOf(archivoXML));

        // Convertir el ReadTree de JSON a formato JSON
        return jsonMapper.writeValueAsString(rootNode);
    }

    // MÉTODO PARA SACAR EL IDENTIFICADOR DEL BOE
    protected String sacarId(Document boe) {
        Document metadatos = boe.get("metadatos", boe.getClass());
        if (metadatos == null) {
            return "no hay id";
        }
        return metadatos.getString("identificador");
    }

    // MÉTODO PARA OBTENER LAS URLs DE TODAS LAS PUBLICACIONES DEL BOE
    protected List<String> SacarUrl(Date fecha) {
        // URL del BOE
        String sumarioURL = "https://www.boe.es/diario_boe/xml.php?id=BOE-S-" + fechaActual(fecha);

        List<String> listaUrls = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(new URL(sumarioURL).openStream());

            XPathFactory xPathfactory = XPathFactory.newInstance();
            XPath xpath = xPathfactory.newXPath();

            // Aquí especificamos la expresión XPath para obtener las URLs de las publicaciones
            String xpathExpression = "//urlXml";
            NodeList nodeList = (NodeList) xpath.compile(xpathExpression).evaluate(doc, XPathConstants.NODESET);

            for (int i = 0; i < nodeList.getLength(); i++) {
                String url = nodeList.item(i).getTextContent();
                listaUrls.add(url);
            }
        } catch (ParserConfigurationException | SAXException | IOException | XPathExpressionException e) {
            e.printStackTrace();
        }
        return listaUrls;
    }

    protected boolean almacenarEnMongoDB(String jsonString) {
        try (MongoClient mongoClient = MongoClients.create(MONGO_DB_URL)) {
            MongoDatabase database = mongoClient.getDatabase(MONGO_DB_NAME);
            MongoCollection<Document> collection = database.getCollection(MONGO_COLLECTION_NAME);
            Document document = Document.parse(jsonString);

            String identificador = sacarId(document);
            Document existingBOE = collection.find(new Document("metadatos.identificador", identificador)).first();

            // Insertar solo si no existe
            if (existingBOE == null) {
                collection.insertOne(document);
                System.out.println("BOE insertado con éxito: " + identificador);
                return false;
            } else {
                System.out.println("BOE ya existe: " + identificador);
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return true;
    }

    protected void almacenarEnNeo4j(String jsonString) {

        ObjectMapper jsonMapper = new ObjectMapper();

        // Conexión a neo4j
        try (Driver driver = GraphDatabase.driver(NEO4J_URL, AuthTokens.basic(NEO4J_USER, NEO4J_PASSWORD));
             Session session = driver.session()) {

            // Almacenamiento de metadatos en los nodos

            Map<String, Object> doc = jsonMapper.readValue(jsonString, Map.class);
            Map<String, Object> metadatos = (Map<String, Object>) doc.get("metadatos");
            String ide = (String) metadatos.get("identificador");
            String title = (String) metadatos.get("titulo");

            String origenLegislativo;
            if ((Map<String, Object>) metadatos.get("origen_legislativo") == null) {
                origenLegislativo = "No tiene origen legislativo";
            } else {
                Map<String, Object> sacarOrigen = (Map<String, Object>) metadatos.get("origen_legislativo");
                origenLegislativo = (String) sacarOrigen.get("");
            }

            String departamento;
            if ((Map<String, Object>) metadatos.get("departamento") == null) {
                departamento = "No tiene departamento";
            } else {
                Map<String, Object> sacarDepartamento = (Map<String, Object>) metadatos.get("departamento");
                departamento = (String) sacarDepartamento.get("");
            }

            String rango;
            if ((Map<String, Object>) metadatos.get("rango") == null) {
                rango = "No tiene rango";
            } else {
                Map<String, Object> sacarRango = (Map<String, Object>) metadatos.get("rango");
                rango = (String) sacarRango.get("");
            }

            // Crear nodos en Neo4j
            String query = "CREATE (n:BOE {identificador: $ide, titulo: $title, departamento:$departamento, origen_legislativo:$origenLegislativo, " +
                    "rango:$rango})";
            session.run(query, parameters("ide", ide, "title", title, "departamento", departamento, "origenLegislativo", origenLegislativo,
                    "rango", rango));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

