package br.edu.aps.inversaotermica;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.*;
import java.net.http.*;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class ConsultaTest {
 @TempDir Path pasta;
 @Test void filtraCombinaPaginaEComparaFusos() throws Exception {
  Path arquivo=pasta.resolve("consulta.db");
  HttpServer servidor=Api.criarServidor(0, arquivo); servidor.start();
  try {
   Banco banco=new Banco(arquivo);
   banco.salvar(new Leitura("A", "2026-09-18T12:00:00-03:00", 10.0, 20.0, 10.0));
   banco.salvar(new Leitura("B", "2026-09-18T15:00:00Z", 50.0, 25.0, 15.0));
   banco.salvar(new Leitura("A", "2026-09-19T15:00:00Z", 80.0, 30.0, 20.0));
   assertEquals(3, json(servidor, "").get("total").getAsInt());
   var pagina=json(servidor,"?limite=1&offset=1");
   assertEquals(3,pagina.get("total").getAsInt());
   assertEquals("B",pagina.getAsJsonArray("leituras").get(0).getAsJsonObject().get("estacao").getAsString());
   assertEquals(1,json(servidor,"?sensor=A&data=2026-09-18&umidade_min=10&umidade_max=10").get("total").getAsInt());
   assertEquals(2,json(servidor,"?data_inicio=2026-09-18T15:00:00Z&data_fim=2026-09-18T15:00:00Z").get("total").getAsInt());
   assertEquals(1,json(servidor,"?umidade=50").get("total").getAsInt());
   assertEquals(0,json(servidor,"?sensor="+URLEncoder.encode("' OR 1=1 --",java.nio.charset.StandardCharsets.UTF_8)).get("total").getAsInt());
   assertEquals(0,json(servidor,"?offset=100").getAsJsonArray("leituras").size());
   try(var c=DriverManager.getConnection("jdbc:sqlite:"+arquivo);var sql=c.createStatement()) {
    sql.execute("DROP TABLE leituras_inversaotermica");
   }
   assertEquals(503,get(servidor, "").statusCode());
  } finally { servidor.stop(0); }
 }
 @Test void rejeitaFiltrosInvalidos() throws Exception {
  var servidor=Api.criarServidor(0,pasta.resolve("invalidos.db")); servidor.start();
  try {
   for(String query: new String[]{"?inexistente=1","?sensor=","?sensor=A&sensor=B","?data=2026-02-30","?data_inicio=ontem","?data_inicio=2026-09-19T00:00:00Z&data_fim=2026-09-18T00:00:00Z","?umidade_min=90&umidade_max=10","?umidade=NaN","?umidade_max=Infinity","?limite=0","?limite=1001","?offset=-1"})
    assertEquals(400,get(servidor,query).statusCode(),query);
  } finally { servidor.stop(0); }
 }
 private HttpResponse<String> get(HttpServer s,String query) throws Exception {
  return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+s.getAddress().getPort()+"/inversao-termica/historico"+query)).GET().build(),HttpResponse.BodyHandlers.ofString());
 }
 private JsonObject json(HttpServer s,String query) throws Exception {
  var r=get(s,query);assertEquals(200,r.statusCode(),r.body());return JsonParser.parseString(r.body()).getAsJsonObject();
 }
}
