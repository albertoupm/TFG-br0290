import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;


// COMANDO PARA EL DOCKER:  docker exec -it prueba mongosh
// BORRAR NODOS EN NEO4J: MATCH (n) OPTIONAL MATCH (n)-[r]-() DELETE n, r;

public class Main {
    public static void main(String[] args) throws IOException, ParseException {
        DescargadorBOE tfg = new DescargadorBOE();
        Funcionalidades tfg1 = new Funcionalidades();
      //  tfg.descargarBOE();

        // tfg.getBoeId("BOE-A-2024-9027"); // ESTE SACA LA INFO DEL BOE QUE SE ALMACENA EN EL NEO4J
       // tfg.mostarBOE("BOE-A-2024-9017"); // ESTE SACA LA INFO QUE SE GUARDA EN EL MONGODB, ES DECIR, EL BOE COMPLETO
      //  tfg.getBoeDepartamento("Ministerio de Hacienda"); // DEVUELVE TODOS LOS BOE ALMACENADOS SEGÚN EL DEPARTAMENTO
      tfg1.evolucionBOE("BOE-A-2024-5481",2); // ESTE ES EL METODO DE LAS REFERENCIAS

        /*Date fechaInicio = new SimpleDateFormat("yyyy-MM-dd").parse("2024-05-3");
        Date fechaFin = new SimpleDateFormat("yyyy-MM-dd").parse("2024-05-6");
        tfg.descargarBOEdesdeHasta(fechaInicio,fechaFin);*/

       // tfg.getBoeDepartamentoYRango("Ministerio de Hacienda", "Resolución");
       // tfg.getBoeDepartamentoYRangoMongo("Consejo General del Poder Judicial", "Acuerdo");
    }
}
