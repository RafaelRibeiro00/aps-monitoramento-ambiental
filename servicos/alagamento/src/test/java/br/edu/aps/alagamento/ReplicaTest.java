package br.edu.aps.alagamento;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.*;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;
class ReplicaTest {
 @TempDir Path pasta;
 @Test void preservaRotasIdentidadeEEstadoCompartilhado() throws Exception {
  var backend=Api.criarServidor(0,pasta.resolve("replica.db"));backend.start();
  var replica=Replica.criar(0,"http://127.0.0.1:"+backend.getAddress().getPort());replica.start();
  var client=HttpClient.newHttpClient();
  String url="http://127.0.0.1:"+replica.getAddress().getPort();
  try {
   var response=client.send(HttpRequest.newBuilder(URI.create(url+"/alagamento/historico")).GET().build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(200,response.statusCode());assertTrue(response.body().contains("leituras"));
   assertEquals("replica-local",response.headers().firstValue("X-Pod").orElseThrow());
   assertEquals("local",response.headers().firstValue("X-Backend-Pod").orElseThrow());
   assertTrue(response.headers().firstValue("X-Versao").isPresent());
   response=client.send(HttpRequest.newBuilder(URI.create(url+"/alagamento/leituras")).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(400,response.statusCode());
   response=client.send(HttpRequest.newBuilder(URI.create(url+"/health/ready")).GET().build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(200,response.statusCode());
   backend.stop(0);
   response=client.send(HttpRequest.newBuilder(URI.create(url+"/health/ready")).GET().build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(503,response.statusCode());
  } finally {replica.stop(0);backend.stop(0);}
 }
}
