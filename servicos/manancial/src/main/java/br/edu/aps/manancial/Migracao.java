package br.edu.aps.manancial;
import java.sql.*;
import java.util.*;
final class Migracao {
    static void aplicar(String url) throws SQLException {
        try(Connection c=DriverManager.getConnection(url);var s=c.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA foreign_keys=OFF");s.execute("BEGIN IMMEDIATE");
            try {
                if("manancial".equals("alagamento")) reconstruir(c,"leituras_manancial",false);
                reconstruir(c,"alertas_manancial",true);
                adicionar(c,"leituras_manancial","evento_id","TEXT");
                adicionar(c,"alertas_manancial","evento_id","TEXT");
                s.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_leituras_manancial_evento ON leituras_manancial(evento_id)");
                s.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_alertas_manancial_evento ON alertas_manancial(evento_id)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_alertas_manancial_envio ON alertas_manancial(status_notificacao,proxima_tentativa)");
                if("manancial".equals("alagamento")) {
                    adicionar(c,"leituras_manancial","ponto_id","TEXT");
                    adicionar(c,"leituras_manancial","status","TEXT");
                    s.execute("UPDATE leituras_manancial SET ponto_id=sensor WHERE ponto_id IS NULL");
                    s.execute("UPDATE leituras_manancial SET status=CASE WHEN nivel_corrego_cm IS NULL OR chuva_mm IS NULL OR velocidade_agua_m_s IS NULL THEN 'incompleta' ELSE 'completa' END WHERE status IS NULL");
                    s.execute("CREATE TABLE IF NOT EXISTS medicoes_alagamento (evento_id TEXT PRIMARY KEY, sensor_id TEXT NOT NULL, ponto_id TEXT NOT NULL, tipo TEXT NOT NULL, valor REAL NOT NULL, unidade TEXT NOT NULL, timestamp TEXT NOT NULL, recebido_em TEXT NOT NULL, conjunto_id TEXT)");
                    s.execute("CREATE INDEX IF NOT EXISTS idx_medicoes_ponto ON medicoes_alagamento(ponto_id,timestamp)");
                }
                try(var erros=s.executeQuery("PRAGMA foreign_key_check")) { if(erros.next()) throw new SQLException("Migracao violaria referencias do banco."); }
                s.execute("COMMIT");
            } catch(SQLException e) { s.execute("ROLLBACK");throw e; }
        }
    }
    private static void reconstruir(Connection c,String tabela,boolean alerta) throws SQLException {
        String sql;
        try(var p=c.prepareStatement("SELECT sql FROM sqlite_master WHERE name=?")) {p.setString(1,tabela);try(var r=p.executeQuery()){if(!r.next())return;sql=r.getString(1);}}
        String novo=alerta?sql.replace("leitura_id INTEGER NOT NULL UNIQUE", "leitura_id INTEGER"):sql.replace("nivel_corrego_cm REAL NOT NULL", "nivel_corrego_cm REAL");
        if(novo.equals(sql))return;
        long sequencia=0;
        try(var p=c.prepareStatement("SELECT seq FROM sqlite_sequence WHERE name=?")){p.setString(1,tabela);try(var r=p.executeQuery()){if(r.next())sequencia=r.getLong(1);}}
        try(var s=c.createStatement()) {
            s.execute(novo.replaceFirst(tabela,tabela+"_migracao"));
            s.execute("INSERT INTO "+tabela+"_migracao SELECT * FROM "+tabela);
            s.execute("DROP TABLE "+tabela);
            s.execute("ALTER TABLE "+tabela+"_migracao RENAME TO "+tabela);
        }
        try(var p=c.prepareStatement("UPDATE sqlite_sequence SET seq=max(seq,?) WHERE name=?")){p.setLong(1,sequencia);p.setString(2,tabela);p.executeUpdate();}
    }
    private static void adicionar(Connection c,String tabela,String coluna,String tipo) throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("PRAGMA table_info("+tabela+")")){while(r.next())if(coluna.equals(r.getString("name")))return;}
        try(var s=c.createStatement()){s.execute("ALTER TABLE "+tabela+" ADD COLUMN "+coluna+" "+tipo);}
    }
}
