package br.edu.aps.alagamento;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.*;
import java.net.http.*;
import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AlertaTest {
 @TempDir Path pasta;
 private Leitura critica() { return new Leitura("SENSOR-TESTE", "2026-09-18T15:00:00Z", 200.0, 0.0, 1.0); }
 @Test void geraSomenteQuandoNecessarioEPersisteComALeitura() throws Exception {
  Path arquivo=pasta.resolve("alertas.db"); Banco banco=new Banco(arquivo);
  assertTrue(((java.util.List<?>)banco.salvar(new Leitura("NORMAL", "2026-09-18T15:00:00Z", 10.0, 0.0, 1.0)).get("alertas")).isEmpty());
  var salvo=banco.salvar(critica());
  assertEquals(1,((java.util.List<?>)salvo.get("alertas")).size());
  banco=new Banco(arquivo);
  assertEquals(1L,banco.consultarAlertas(Consulta.deQuery("sensor=SENSOR-TESTE&status_notificacao=pendente",true)).get("total"));
  assertEquals(0L,banco.consultarAlertas(Consulta.deQuery("sensor=NORMAL",true)).get("total"));
  try(var c=DriverManager.getConnection("jdbc:sqlite:"+arquivo);var sql=c.createStatement()) { sql.execute("DROP TABLE alertas_alagamento"); }
  Banco mesmo=banco;
  assertThrows(java.sql.SQLException.class,()->mesmo.salvar(critica()));
  assertEquals(2L,banco.consultar(Consulta.deQuery(null)).get("total"),"Falha no alerta deve reverter tambem a leitura");
 }
 @Test void webhookRepeteFalhaSemDuplicarLeituraOuAlerta() throws Exception {
  Path arquivo=pasta.resolve("webhook.db");Banco banco=new Banco(arquivo);banco.salvar(critica());
  AtomicInteger chamadas=new AtomicInteger();AtomicReference<String> chave=new AtomicReference<>();
  AtomicReference<String> corpo=new AtomicReference<>();
  var destino=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  destino.createContext("/notificacoes",r->{
   try {
    corpo.set(new String(r.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
    String atual=r.getRequestHeaders().getFirst("Idempotency-Key");
    if(chave.get()==null)chave.set(atual);else assertEquals(chave.get(),atual);
    r.sendResponseHeaders(chamadas.incrementAndGet()==1?503:204,-1);
   } finally {r.close();}
  });destino.start();
  try(var n=new Notificador(banco,"http://127.0.0.1:"+destino.getAddress().getPort()+"/notificacoes","")) {
   n.processarPendentes();assertEquals(1,chamadas.get());
   assertEquals(1L,banco.consultarAlertas(Consulta.deQuery("status_notificacao=pendente",true)).get("total"));
   n.processarPendentes();assertEquals(1,chamadas.get(),"Respeita espera entre tentativas");
   try(var c=DriverManager.getConnection("jdbc:sqlite:"+arquivo);var sql=c.createStatement()) {sql.execute("UPDATE alertas_alagamento SET proxima_tentativa=0");}
   n.processarPendentes();n.processarPendentes();assertEquals(2,chamadas.get());
   assertEquals(1L,banco.consultarAlertas(Consulta.deQuery("status_notificacao=enviado",true)).get("total"));
   assertEquals(1L,banco.consultar(Consulta.deQuery(null)).get("total"));
   assertEquals("alagamento",JsonParser.parseString(corpo.get()).getAsJsonObject().get("servico").getAsString());
   assertNotNull(chave.get());
  } finally {destino.stop(0);}
 }
 @Test void rotasHttpMostramAlertaEGeracaoAutomatica() throws Exception {
  var servidor=Api.criarServidor(0,pasta.resolve("http.db"));servidor.start();
  try {
   String base="http://127.0.0.1:"+servidor.getAddress().getPort();var cliente=HttpClient.newHttpClient();
   var resposta=cliente.send(HttpRequest.newBuilder(URI.create(base+"/leituras")).POST(HttpRequest.BodyPublishers.ofString(critica().paraJson())).build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(200,resposta.statusCode());assertEquals(1,JsonParser.parseString(resposta.body()).getAsJsonObject().getAsJsonArray("alertas").size());
   var consulta=cliente.send(HttpRequest.newBuilder(URI.create(base+"/alertas?sensor=SENSOR-TESTE&data=2026-09-18")).GET().build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(200,consulta.statusCode());assertEquals(1,JsonParser.parseString(consulta.body()).getAsJsonObject().get("total").getAsInt());
   assertEquals(400,cliente.send(HttpRequest.newBuilder(URI.create(base+"/alertas?status_notificacao=invalido")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
   assertEquals(405,cliente.send(HttpRequest.newBuilder(URI.create(base+"/alertas")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
  } finally {servidor.stop(0);}
 }
}
