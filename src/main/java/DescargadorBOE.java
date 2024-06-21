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
            //  urlBOE = "https://www.boe.es/diario_boe/xml.php?id=BOE-A-2024-5481";
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
    // FUNCIONALIDADES


    // MÉTODO PARA PASAR COMPLETO EL MONGO DB A NEO4J
    /*public void almacenarNeo4j() {
        try (MongoClient mongoClient = MongoClients.create(MONGO_DB_URL)) {
            MongoDatabase database = mongoClient.getDatabase(MONGO_DB_NAME);
            MongoCollection<Document> collection = database.getCollection(MONGO_COLLECTION_NAME);
            Document document = new Document();
            Driver driver = GraphDatabase.driver(NEO4J_URL, AuthTokens.basic(NEO4J_USER, NEO4J_PASSWORD));
            Session session = driver.session();

            FindIterable<Document> documents = collection.find();
            MongoCursor<Document> cursor = documents.iterator();
            ObjectMapper jsonMapper = new ObjectMapper();

            // Almacenamiento de metadatos en los nodos
            while (cursor.hasNext()) {

                document = cursor.next();
                Map<String, Object> doc = jsonMapper.readValue(document.toJson(), Map.class);
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
            }
            System.out.println("Datos transferidos de MongoDB a Neo4j con éxito.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // MÉTODO QUE RETORNA UN BOE POR SU IDENTIFICADOR DESDE NEO4J
    public void getBoeId(String identificador) {
        Driver driver = GraphDatabase.driver(NEO4J_URL, AuthTokens.basic(NEO4J_USER, NEO4J_PASSWORD));
        Session session = driver.session();
        String query = "MATCH (n:BOE {identificador: $identificador}) RETURN n";

        Result result = session.run(query, parameters("identificador", identificador));
        Record record = result.next();
        Node node = record.get("n").asNode();
        node.keys().forEach(key -> System.out.println(key + ": " + node.get(key).asString()));
    }

    // MÉTODO QUE DEVUELVE LAS N(PROFUNDIDAD) REFERENCIAS EN DADO UN BOE https://www.boe.es/diario_boe/xml.php?id=BOE-A-2024-5481
    public void evolucionBOE(String identificador, int profundidad) throws IOException {
        if(profundidad > 0) {
            // OBTENEMOS LAS URLS DE LAS REFERENCIAS
            List<String> lista = getUrlReferencias(identificador);
            // GUARDAMOS LAS REFERENCIAS
            guardar(lista, identificador);
            if (profundidad > 1) {
                for (String referencia : lista) {
                    evolucionBOE(referencia, profundidad - 1);
                }
            }
        }
    }

    // MÉTDODO PARA SACAR LAS URLS DE LAS REFERENCIAS DE UN BOE
    private List<String> getUrlReferencias(String identificador) {
        List<String> listaUrls = new ArrayList<>();
        String urlBoe = "https://www.boe.es/diario_boe/xml.php?id=" + identificador;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(new URL(urlBoe).openStream());

            XPathFactory xPathfactory = XPathFactory.newInstance();
            XPath xpath = xPathfactory.newXPath();

            // Expresión XPath para obtener los nodos de referencia
            String xpathExpression = "//referencias/anteriores/anterior/@referencia";
            NodeList nodeList = (NodeList) xpath.compile(xpathExpression).evaluate(doc, XPathConstants.NODESET);

            for (int i = 0; i < nodeList.getLength(); i++) {
                String referencia = nodeList.item(i).getTextContent();
                listaUrls.add(referencia);
            }

        } catch (ParserConfigurationException | SAXException | IOException | XPathExpressionException e) {
            e.printStackTrace();
        }

        return listaUrls;
    }

    // MÉTODO PARA DESCARGAR LOS XML Y ALMACENARLOS EN MONGODB Y NEO4J
    private void guardar(List<String> lista, String nodoInicial) throws IOException {
        String nodoA = nodoInicial;

        for (String urlAux : lista) {

            String urlBOE = "https://www.boe.es/diario_boe/xml.php?id=" + urlAux;
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
            // CREAMOS LOS NODOS Y RELACIONAMOS LOS NODOS
            String nodoB = urlAux;
            almacenar(contenidoXML);
            relacionar(nodoA, nodoB);
        }
    }

    // MÉTODO PARA CREAR EL NODO EN NEO4J - NO EN USO ACTUALMENTE
    private String crearNodo(StringBuilder archivoXML) {
        try {
            // Inicializar ObjectMapper para XML y JSON
            XmlMapper xmlMapper = new XmlMapper();
            ObjectMapper jsonMapper = new ObjectMapper();

            // Leer el archivo XML y convertirlo a un ReadTree de JSON
            JsonNode rootNode = xmlMapper.readTree(String.valueOf(archivoXML));

            // Convertir el ReadTree de JSON a formato JSON
            String jsonString = jsonMapper.writeValueAsString(rootNode);

            // Pasar el JSON al contenedor de DOCKER MONGODB
            try (MongoClient mongoClient = MongoClients.create(MONGO_DB_URL)) {
                MongoDatabase database = mongoClient.getDatabase(MONGO_DB_NAME);
                MongoCollection<Document> collection = database.getCollection(MONGO_COLLECTION_NAME);
                Document document = Document.parse(jsonString);

                String idDuplicado = sacarId(document);
                Document existingBOE = collection.find(new Document("metadatos.identificador", idDuplicado)).first();

                // Insertar solo si no existe
                if (existingBOE == null) {
                    collection.insertOne(document);

                    // Conexión a neo4j
                    Driver driver = GraphDatabase.driver(NEO4J_URL, AuthTokens.basic(NEO4J_USER, NEO4J_PASSWORD));
                    Session session = driver.session();

                    // Almacenamiento de metadatos en los nodos

                    Map<String, Object> doc = jsonMapper.readValue(document.toJson(), Map.class);
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

                    return ide;
                } else {
                    System.out.println("BOE ya existe: " + idDuplicado);
                    return idDuplicado;
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "No se ha creado correctamente";
    }

    // MÉTODO PARA CREAR LA RELACION EN NEO4J

    private void relacionar(String idNodoA, String idNodoB) {
        try (Driver driver = GraphDatabase.driver(NEO4J_URL, AuthTokens.basic(NEO4J_USER, NEO4J_PASSWORD));
             Session session = driver.session()) {

            // Consulta para crear la relación entre los nodos utilizando sus identificadores
            String query = "MATCH (n1:BOE), (n2:BOE) " +
                    "WHERE  n1.identificador = $idNodoA AND  n2.identificador = $idNodoB " +
                    "MERGE (n1)-[r:REFIERE]->(n2)";

            // Ejecutar la consulta con los identificadores de los nodos
            session.run(query, parameters("idNodoA", idNodoA, "idNodoB", idNodoB));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // MÉTDOO QUE DEVUELVE TODOS LOS BOE PUBLICADOS (ALMACENADOS EN EL NEO4J) DADO EL NOMBRE DE UN DEPARTAMENTO
    public void getBoeDepartamento(String departamento) {
        Driver driver = GraphDatabase.driver(NEO4J_URL, AuthTokens.basic(NEO4J_USER, NEO4J_PASSWORD));
        Session session = driver.session();
        String query = "MATCH (n:BOE {departamento: $departamento}) RETURN n";

        Result result = session.run(query, parameters("departamento", departamento));
        while (result.hasNext()) {
            Record record = result.next();
            Node node = record.get("n").asNode();
            // System.out.println(node.asMap()); OTRA FORMA DE MOSTRAR EL RESULTADO
            node.keys().forEach(key -> System.out.println(key + ": " + node.get(key).asString()));
        }
    }

    public void mostarBOE(String identificador) {
        try (MongoClient mongoClient = MongoClients.create(MONGO_DB_URL)) {
            MongoDatabase database = mongoClient.getDatabase(MONGO_DB_NAME);
            MongoCollection<Document> collection = database.getCollection(MONGO_COLLECTION_NAME);
            Document document = collection.find(new Document("metadatos.identificador", identificador)).first();
            System.out.println(document);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void descargarBOEdesdeHasta(Date fechaIni, Date fechaFin) {
        Calendar calendario = GregorianCalendar.getInstance();
        Date fechaHoy = calendario.getTime();
        if (fechaIni.after(fechaHoy) || fechaFin.after(fechaHoy)) {
            System.out.println("Rango de fechas no válido");
        } else if (fechaIni.after(fechaFin)) {
            System.out.println("Rango de fechas no válido");
        } else {
            calendario.setTime(fechaIni);
            while (!calendario.getTime().after(fechaFin)) {
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
                calendario.add(Calendar.DAY_OF_MONTH, 1);
            }
        }
    }

    // FILTRADO DOBLE
    public void getBoeDepartamentoYRango(String departamento, String rango) {
        Driver driver = GraphDatabase.driver(NEO4J_URL, AuthTokens.basic(NEO4J_USER, NEO4J_PASSWORD));
        Session session = driver.session();
        String query = "MATCH (n:BOE {departamento: $departamento, rango: $rango}) RETURN n";

        Result result = session.run(query, parameters("departamento", departamento, "rango", rango));
        while (result.hasNext()) {
            Record record = result.next();
            Node node = record.get("n").asNode();
            node.keys().forEach(key -> System.out.println(key + ": " + node.get(key).asString()));
        }
    }

    public void getBoeDepartamentoYRangoMongo(String departamento, String rango) {
        try (MongoClient mongoClient = MongoClients.create(MONGO_DB_URL)) {
            MongoDatabase database = mongoClient.getDatabase(MONGO_DB_NAME);
            MongoCollection<Document> collection = database.getCollection(MONGO_COLLECTION_NAME);

            Document filter = new Document("metadatos.departamento.", departamento).append("metadatos.rango.", rango);

            try (MongoCursor<Document> cursor = collection.find(filter).iterator()) {
                while (cursor.hasNext()) {
                    Document doc = cursor.next();
                    System.out.println(doc.toJson());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void ejemplo() {

        String lista = "xml.php?id=BOE-A-2024-10761";
            // URL del BOE
            String urlBOE = "https://www.boe.es/diario_boe/xml.php?id=BOE-A-2024-10761";
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
            try (InputStream inputStream = conexion.getInputStream();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int bytesLeidos;
                while ((bytesLeidos = inputStream.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesLeidos);
                }
                // Transformar XML a JSON
                StringBuilder contenidoXML = new StringBuilder(baos.toString("UTF-8"));
                String contenidoJSON = transformador(contenidoXML);

                // Almacenar en Local
                int indice = lista.indexOf("id=") + 3;
                String nombreArchivo = lista.substring(indice) + ".json";
                File archivo = new File(nombreArchivo);

                try (BufferedWriter escritor = new BufferedWriter(new FileWriter(archivo))) {
                    escritor.write(contenidoJSON);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }*/
}

