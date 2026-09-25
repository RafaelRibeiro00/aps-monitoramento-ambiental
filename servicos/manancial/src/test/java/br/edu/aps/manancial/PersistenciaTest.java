package br.edu.aps.manancial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class PersistenciaTest {
 @TempDir Path pasta;
 @Test void filaSobreviveAoReinicio() throws Exception {
  Path arquivo=pasta.resolve("persistencia.db");Banco banco=new Banco(arquivo);
  Fluxo fluxo=new Fluxo(banco,arquivo);
  try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+arquivo);var sql=c.createStatement()) {
   sql.execute("BEGIN IMMEDIATE");
   fluxo.receber(RuntimeTest.CORPO);
   fluxo.close();
   sql.execute("ROLLBACK");
  }
  try(Fluxo reiniciado=new Fluxo(new Banco(arquivo),arquivo)) {
   RuntimeTest.aguardar(()->RuntimeTest.contar(arquivo,"leituras_manancial")>=1);
  }
 }
}
