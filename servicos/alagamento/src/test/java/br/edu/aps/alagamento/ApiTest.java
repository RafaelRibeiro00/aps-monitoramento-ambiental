package br.edu.aps.alagamento;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import com.google.gson.*;
import static org.junit.jupiter.api.Assertions.*;
class ApiTest {
 @TempDir Path pasta;
 @Test void validaContratoDeSensorIndividual() throws Exception {
  var servidor=Api.criarServidor(0,pasta.resolve("validacao.db"));servidor.start();String base="http://127.0.0.1:"+servidor.getAddress().getPort()+"/alagamento";
  try {
   for(String campo:new String[]{"sensor_id","ponto_id","tipo","valor","unidade","timestamp"}) {
    var json=JsonParser.parseString(RuntimeTest.CORPO).getAsJsonObject();json.remove(campo);assertEquals(400,RuntimeTest.pedido(base,"/leituras",json.toString()).statusCode(),campo);
    json=JsonParser.parseString(RuntimeTest.CORPO).getAsJsonObject();json.add(campo,JsonNull.INSTANCE);assertEquals(400,RuntimeTest.pedido(base,"/leituras",json.toString()).statusCode(),campo);
   }
   for(String errado:new String[]{"{}","[]","null",RuntimeTest.CORPO+RuntimeTest.CORPO,RuntimeTest.CORPO.replace("210","-1"),RuntimeTest.CORPO.replace("210","1e999"),RuntimeTest.CORPO.replace("cm","m"),RuntimeTest.CORPO.replace("2026-09-24","2026-02-30")})assertEquals(400,RuntimeTest.pedido(base,"/leituras",errado).statusCode(),errado);
   assertEquals(405,RuntimeTest.pedido(base,"/leituras",null).statusCode());
   assertEquals(202,RuntimeTest.pedido(base,"/leituras",RuntimeTest.CORPO).statusCode());
   assertEquals(409,RuntimeTest.pedido(base,"/leituras",RuntimeTest.CORPO).statusCode());
  }finally{servidor.stop(0);}
 }
 @Test void consolidaTresSensoresOuNullAposPrazo() throws Exception {
  Path arquivo=pasta.resolve("grupos.db");var servidor=Api.criarServidor(0,arquivo);servidor.start();String base="http://127.0.0.1:"+servidor.getAddress().getPort()+"/alagamento";
  try {
   assertEquals(202,RuntimeTest.pedido(base,"/leituras",RuntimeTest.CORPO).statusCode());
   String chuva=RuntimeTest.CORPO.replace("N-1","C-1").replace("nivel_corrego","chuva").replace("cm","mm").replace("210","20").replace("15:00:00","15:00:01");
   String velocidade=RuntimeTest.CORPO.replace("N-1","V-1").replace("nivel_corrego","velocidade_agua").replace("cm","m/s").replace("210","2").replace("15:00:00","15:00:02");
   assertEquals(409,RuntimeTest.pedido(base,"/leituras",chuva.replace("15:00:01","15:00:04")).statusCode());
   assertEquals(202,RuntimeTest.pedido(base,"/leituras",chuva).statusCode());assertEquals(202,RuntimeTest.pedido(base,"/leituras",velocidade).statusCode());
   RuntimeTest.aguardar(()->RuntimeTest.contar(arquivo,"leituras_alagamento")==1);
   var dados=JsonParser.parseString(RuntimeTest.pedido(base,"/historico",null).body()).getAsJsonObject().getAsJsonArray("leituras").get(0).getAsJsonObject();
   assertEquals("completa",dados.get("status").getAsString());assertEquals("2026-09-24T15:00:02Z",dados.get("timestamp").getAsString());assertEquals(2,dados.get("velocidade_agua_m_s").getAsInt());
   assertEquals(202,RuntimeTest.pedido(base,"/leituras",chuva.replace("PONTO-TESTE","OUTRO")).statusCode());
   RuntimeTest.aguardar(()->RuntimeTest.contar(arquivo,"leituras_alagamento")==2);
   var incompleto=JsonParser.parseString(RuntimeTest.pedido(base,"/historico?ponto_id=OUTRO",null).body()).getAsJsonObject().getAsJsonArray("leituras").get(0).getAsJsonObject();
   assertEquals("incompleta",incompleto.get("status").getAsString());assertTrue(incompleto.get("nivel_corrego_cm").isJsonNull());assertTrue(incompleto.get("velocidade_agua_m_s").isJsonNull());
   assertEquals(4,RuntimeTest.contar(arquivo,"medicoes_alagamento"));
  }finally{servidor.stop(0);}
 }
}
