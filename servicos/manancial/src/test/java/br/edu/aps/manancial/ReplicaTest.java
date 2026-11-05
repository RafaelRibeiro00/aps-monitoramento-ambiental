package br.edu.aps.manancial;
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
   var response=client.send(HttpRequest.newBuilder(URI.create(url+"/manancial/historico")).GET().build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(200,response.statusCode());assertTrue(response.body().contains("leituras"));
   assertEquals("replica-local",response.headers().firstValue("X-Pod").orElseThrow());
   assertEquals("local",response.headers().firstValue("X-Backend-Pod").orElseThrow());
   assertTrue(response.headers().firstValue("X-Versao").isPresent());
   response=client.send(HttpRequest.newBuilder(URI.create(url+"/manancial/leituras")).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(400,response.statusCode());
   response=client.send(HttpRequest.newBuilder(URI.create(url+"/health/ready")).GET().build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(200,response.statusCode());
   backend.stop(0);
   response=client.send(HttpRequest.newBuilder(URI.create(url+"/health/ready")).GET().build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(503,response.statusCode());
  } finally {replica.stop(0);backend.stop(0);}
 }

 @Test void modoSincronoConfirmaCommitAntesDoRetorno() throws Exception {
  System.setProperty("aps.escrita","sincrona");
  Path arquivo=pasta.resolve("sincrono.db");
  try(Fluxo fluxo=new Fluxo(new Banco(arquivo),arquivo)) {
   var response=fluxo.receber(RuntimeTest.CORPO);
   assertEquals("concluida",response.get("persistencia"));
   assertEquals(1,RuntimeTest.contar(arquivo,"leituras_manancial"));
   assertEquals(0,fluxo.fila.tamanho());
  } finally {System.clearProperty("aps.escrita");}
 }
}
