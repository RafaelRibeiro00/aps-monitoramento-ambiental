package br.edu.aps.alagamento;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Abre conexoes curtas; cada INSERT e confirmado antes de responder ao cliente. */
public final class Banco {
    private final String url;
    private final Path arquivo;

    public static Path caminhoPadrao() {
        String configurado = System.getProperty("aps.db", System.getenv("APS_DB"));
        if (configurado != null) return Path.of(configurado);
        Path raiz=Configuracao.raiz();
        if(raiz==null)throw new IllegalStateException("Configure APS_DB ou execute na raiz da APS.");
        Path pasta=Path.of(Configuracao.valor("APS_DATA_DIR","./dados"));
        if(!pasta.isAbsolute())pasta=raiz.resolve(pasta);
        return pasta.resolve(Configuracao.valor("APS_DB_FILE","aps.db"));
    }

    public Banco(Path arquivo) throws IOException {
        Path absoluto = arquivo.toAbsolutePath().normalize();
        this.arquivo = absoluto;
        Files.createDirectories(absoluto.getParent());
        url = "jdbc:sqlite:" + absoluto;
        try (Connection conexao = conectar(); var comando = conexao.createStatement()) {
            comando.execute("BEGIN IMMEDIATE");
            comando.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS leituras_alagamento (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        sensor TEXT NOT NULL,
                        timestamp TEXT NOT NULL,
                        nivel_corrego_cm REAL NOT NULL CHECK (nivel_corrego_cm >= 0),
                        recebido_em TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
                    )
                    """);
            // Leituras anteriores mantêm NULL nos campos que ainda não eram enviados.
            adicionarColuna(conexao, "chuva_mm", "REAL CHECK (chuva_mm >= 0)");
            adicionarColuna(conexao, "velocidade_agua_m_s", "REAL CHECK (velocidade_agua_m_s >= 0)");
            comando.executeUpdate("""
                CREATE TABLE IF NOT EXISTS alertas_alagamento (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    leitura_id INTEGER NOT NULL UNIQUE REFERENCES leituras_alagamento(id) ON DELETE CASCADE,
                    tipo TEXT NOT NULL,
                    mensagem TEXT NOT NULL,
                    detalhes TEXT NOT NULL,
                    criado_em TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    status_notificacao TEXT NOT NULL DEFAULT 'pendente' CHECK(status_notificacao IN ('pendente', 'enviado')),
                    tentativas INTEGER NOT NULL DEFAULT 0,
                    proxima_tentativa INTEGER NOT NULL DEFAULT 0,
                    enviado_em TEXT,
                    ultimo_erro TEXT
                )
                """);
            comando.executeUpdate("CREATE INDEX IF NOT EXISTS idx_alertas_alagamento_envio ON alertas_alagamento(status_notificacao, proxima_tentativa)");
            comando.execute("COMMIT");
        } catch (SQLException e) {
            throw new IOException("Nao foi possivel inicializar o SQLite em " + absoluto, e);
        }
        try { Migracao.aplicar(url); } catch(SQLException e) { throw new IOException("Falha na migracao do banco",e); }
        Log.info("Banco de dados: " + absoluto);
    }

    private static void adicionarColuna(Connection conexao, String nome, String definicao) throws SQLException {
        boolean existe = false;
        try (var comando = conexao.createStatement();
             var colunas = comando.executeQuery("PRAGMA table_info(leituras_alagamento)")) {
            while (colunas.next()) {
                if (nome.equals(colunas.getString("name"))) existe = true;
            }
        }
        if (!existe) {
            // Nome e definição vêm exclusivamente das constantes internas acima.
            try (var comando = conexao.createStatement()) {
                comando.executeUpdate("ALTER TABLE leituras_alagamento ADD COLUMN " + nome + " " + definicao);
            }
        }
    }

    private Connection conectar() throws SQLException {
        java.util.Properties propriedades = new java.util.Properties();
        propriedades.setProperty("busy_timeout", "5000");
        propriedades.setProperty("foreign_keys", "true");
        return DriverManager.getConnection(url, propriedades);
    }

    /** Testa o esquema e a disponibilidade para escrita, sem inserir dados. */
    public boolean pronto() {
        if (!Files.isRegularFile(arquivo)) return false;
        java.util.Properties propriedades = new java.util.Properties();
        propriedades.setProperty("busy_timeout", "500");
        try (Connection conexao = DriverManager.getConnection(url, propriedades);
             var comando = conexao.createStatement()) {
            comando.execute("BEGIN IMMEDIATE");
            try (var resultado = comando.executeQuery("SELECT sensor, timestamp, nivel_corrego_cm, chuva_mm, velocidade_agua_m_s FROM leituras_alagamento LIMIT 0")) {
                // A consulta valida todas as colunas usadas ao receber uma leitura.
            }
            try (var resultado = comando.executeQuery("SELECT id, leitura_id, detalhes, status_notificacao FROM alertas_alagamento LIMIT 0")) {
                // A fila de alertas tambem precisa estar disponivel.
            }
            comando.execute("ROLLBACK");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public java.util.Map<String, Object> consultar(Consulta consulta) throws SQLException {
        try (Connection conexao = conectar()) {
            // Contagem e pagina usam a mesma fotografia do banco.
            conexao.setAutoCommit(false);
            long total;
            try (var comando = conexao.prepareStatement("SELECT count(*) FROM leituras_alagamento" + consulta.where())) {
                preencher(comando, consulta.valores());
                try (var resultado = comando.executeQuery()) { total = resultado.getLong(1); }
            }
            var leituras = new java.util.ArrayList<java.util.Map<String, Object>>();
            try (var comando = conexao.prepareStatement("SELECT * FROM leituras_alagamento" + consulta.where() + " ORDER BY id " + consulta.ordem() + " LIMIT ? OFFSET ?")) {
                preencher(comando, consulta.valores());
                comando.setInt(consulta.valores().size() + 1, consulta.limite());
                comando.setInt(consulta.valores().size() + 2, consulta.offset());
                try (var resultado = comando.executeQuery()) {
                    var colunas = resultado.getMetaData();
                    while (resultado.next()) {
                        var linha = new java.util.LinkedHashMap<String, Object>();
                        for (int i = 1; i <= colunas.getColumnCount(); i++)
                            linha.put(colunas.getColumnName(i), resultado.getObject(i));
                        leituras.add(linha);
                    }
                }
            }
            conexao.commit();
            return java.util.Map.of("total", total, "limite", consulta.limite(), "offset", consulta.offset(), "leituras", leituras);
        }
    }

    private static void preencher(java.sql.PreparedStatement comando, java.util.List<Object> valores) throws SQLException {
        for (int i = 0; i < valores.size(); i++) comando.setObject(i + 1, valores.get(i));
    }

    public java.util.Map<String, Object> salvar(Leitura leitura) throws SQLException {
        leitura.validar();
        var regra = RegrasAlerta.avaliar(leitura);
        var alertas = new java.util.ArrayList<java.util.Map<String, Object>>();
        try (Connection conexao = conectar()) {
            conexao.setAutoCommit(false);
            try (var comando = conexao.prepareStatement("INSERT INTO leituras_alagamento (sensor, timestamp, nivel_corrego_cm, chuva_mm, velocidade_agua_m_s) VALUES (?, ?, ?, ?, ?)")) {
                comando.setString(1, leitura.sensor());
                comando.setString(2, leitura.timestamp());
                comando.setDouble(3, leitura.nivelCorregoCm());
                comando.setDouble(4, leitura.chuva_mm());
                comando.setDouble(5, leitura.velocidade_agua_m_s());
                comando.executeUpdate();
            }
            long id;
            try (var comando = conexao.createStatement(); var resultado = comando.executeQuery("SELECT last_insert_rowid()")) { id = resultado.getLong(1); }
            if (!regra.isEmpty()) {
                var detalhes = new java.util.LinkedHashMap<String, Object>(regra);
                detalhes.put("servico", "alagamento");
                detalhes.put("leitura_id", id);
                detalhes.put("leitura", com.google.gson.JsonParser.parseString(leitura.paraJson()));
                String json = new com.google.gson.Gson().toJson(detalhes);
                try (var comando = conexao.prepareStatement("INSERT INTO alertas_alagamento (leitura_id, tipo, mensagem, detalhes) VALUES (?, ?, ?, ?)")) {
                    comando.setLong(1, id);
                    comando.setString(2, (String)regra.get("tipo"));
                    comando.setString(3, (String)regra.get("mensagem"));
                    comando.setString(4, json);
                    comando.executeUpdate();
                }
                alertas.add(detalhes);
            }
            conexao.commit();
            return java.util.Map.of("mensagem", "Leitura recebida com sucesso.", "leitura_id", id, "alertas", alertas);
        }
    }

    public java.util.Map<String, Object> consultarAlertas(Consulta consulta) throws SQLException {
        String origem = " FROM (SELECT a.*, coalesce(l.sensor,json_extract(a.detalhes,'$.leitura.sensor'),json_extract(a.detalhes,'$.leitura.ponto_id')) AS sensor, coalesce(l.timestamp,json_extract(a.detalhes,'$.leitura.timestamp')) AS timestamp, coalesce(l.nivel_corrego_cm,json_extract(a.detalhes,'$.leitura.nivel_corrego_cm'),CASE WHEN json_extract(a.detalhes,'$.leitura.tipo')='nivel_corrego' THEN json_extract(a.detalhes,'$.leitura.valor') END) AS nivel_corrego_cm, coalesce(l.chuva_mm,json_extract(a.detalhes,'$.leitura.chuva_mm'),CASE WHEN json_extract(a.detalhes,'$.leitura.tipo')='chuva' THEN json_extract(a.detalhes,'$.leitura.valor') END) AS chuva_mm, coalesce(l.velocidade_agua_m_s,json_extract(a.detalhes,'$.leitura.velocidade_agua_m_s'),CASE WHEN json_extract(a.detalhes,'$.leitura.tipo')='velocidade_agua' THEN json_extract(a.detalhes,'$.leitura.valor') END) AS velocidade_agua_m_s FROM alertas_alagamento a LEFT JOIN leituras_alagamento l ON l.id=a.leitura_id)";
        try (Connection conexao = conectar()) {
            conexao.setAutoCommit(false);
            long total;
            try (var comando = conexao.prepareStatement("SELECT count(*)" + origem + consulta.where())) {
                preencher(comando, consulta.valores());
                try (var rs = comando.executeQuery()) { total = rs.getLong(1); }
            }
            var alertas = new java.util.ArrayList<java.util.Map<String, Object>>();
            try (var comando = conexao.prepareStatement("SELECT *" + origem + consulta.where() + " ORDER BY julianday(criado_em) " + consulta.ordem() + ", id " + consulta.ordem() + " LIMIT ? OFFSET ?")) {
                preencher(comando, consulta.valores());
                comando.setInt(consulta.valores().size()+1, consulta.limite());
                comando.setInt(consulta.valores().size()+2, consulta.offset());
                try (var rs = comando.executeQuery()) {
                    while (rs.next()) {
                        var alerta = new java.util.LinkedHashMap<String, Object>();
                        for (String campo : java.util.List.of("id", "evento_id", "leitura_id", "tipo", "mensagem", "criado_em", "status_notificacao", "tentativas", "enviado_em", "ultimo_erro"))
                            alerta.put(campo, rs.getObject(campo));
                        alerta.put("detalhes", com.google.gson.JsonParser.parseString(rs.getString("detalhes")));
                        alertas.add(alerta);
                    }
                }
            }
            conexao.commit();
            return java.util.Map.of("total", total, "limite", consulta.limite(), "offset", consulta.offset(), "alertas", alertas,
                    "webhook_configurado", !Configuracao.valor("ALERTA_WEBHOOK_URL", Configuracao.valor("ALAGAMENTO_WEBHOOK_URL", "")).isBlank());
        }
    }

    /** Reserva atomica impede que dois ciclos enviem o mesmo evento simultaneamente. */
    public java.util.Map<String, Object> reservarNotificacao() throws SQLException {
        try (Connection c = conectar(); var comando = c.createStatement()) {
            comando.execute("BEGIN IMMEDIATE");
            var evento = new java.util.LinkedHashMap<String, Object>();
            try (var rs = comando.executeQuery("SELECT id, detalhes, tentativas FROM alertas_alagamento WHERE status_notificacao='pendente' AND proxima_tentativa <= unixepoch() ORDER BY id LIMIT 1")) {
                if (rs.next()) {
                    evento.put("id", rs.getLong("id"));
                    evento.put("detalhes", rs.getString("detalhes"));
                    evento.put("tentativas", rs.getInt("tentativas") + 1);
                }
            }
            if (!evento.isEmpty()) {
                try (var update = c.prepareStatement("UPDATE alertas_alagamento SET tentativas=tentativas+1, proxima_tentativa=unixepoch()+60 WHERE id=?")) {
                    update.setObject(1, evento.get("id")); update.executeUpdate();
                }
            }
            comando.execute("COMMIT");
            return evento;
        }
    }

    public void registrarEnvio(long id, boolean sucesso, int tentativas, String erro) throws SQLException {
        try (Connection c = conectar(); var comando = c.prepareStatement(
                "UPDATE alertas_alagamento SET status_notificacao=?, enviado_em=CASE WHEN ? THEN strftime('%Y-%m-%dT%H:%M:%fZ','now') ELSE NULL END, ultimo_erro=?, proxima_tentativa=unixepoch()+? WHERE id=?")) {
            comando.setString(1, sucesso ? "enviado" : "pendente");
            comando.setBoolean(2, sucesso);
            comando.setString(3, erro);
            comando.setInt(4, (int)Math.min(300, 5L << Math.min(tentativas - 1, 6)));
            comando.setLong(5, id);
            comando.executeUpdate();
        }
    }

    /** Idempotencia por UUID: retomar a fila nao duplica registros nem alertas. */
    public void persistir(com.google.gson.JsonObject evento) throws SQLException {
        try(Connection c=conectar()) {
            c.setAutoCommit(false);
            persistir(c,evento);
            c.commit();
        }
    }
    private void persistir(Connection c,com.google.gson.JsonObject evento) throws SQLException {
        String eventoId=evento.get("evento_id").getAsString();
        var leitura=evento.getAsJsonObject("leitura");Long leituraId=null;
        if("medicao".equals(evento.get("tipo_evento").getAsString())) {
            try(var p=c.prepareStatement("INSERT OR IGNORE INTO medicoes_alagamento(evento_id,sensor_id,ponto_id,tipo,valor,unidade,timestamp,recebido_em) VALUES (?,?,?,?,?,?,?,?)")) {
                p.setString(1,eventoId);p.setString(2,leitura.get("sensor_id").getAsString());p.setString(3,leitura.get("ponto_id").getAsString());p.setString(4,leitura.get("tipo").getAsString());
                p.setDouble(5,leitura.get("valor").getAsDouble());p.setString(6,leitura.get("unidade").getAsString());p.setString(7,leitura.get("timestamp").getAsString());p.setString(8,evento.get("recebido_em").getAsString());p.executeUpdate();
            }
        } else {
            if(evento.has("medicoes"))for(var medicao:evento.getAsJsonArray("medicoes"))persistir(c,medicao.getAsJsonObject());
            try(var p=c.prepareStatement("INSERT OR IGNORE INTO leituras_alagamento(sensor,timestamp,nivel_corrego_cm, chuva_mm, velocidade_agua_m_s,evento_id) VALUES (?,?,?,?,?,?)")) {
                p.setString(1,leitura.get("sensor").getAsString());p.setString(2,leitura.get("timestamp").getAsString());
                int i=3;for(String campo:java.util.List.of("nivel_corrego_cm","chuva_mm","velocidade_agua_m_s")) {var v=leitura.get(campo);if(v==null||v.isJsonNull())p.setNull(i++,java.sql.Types.REAL);else p.setDouble(i++,v.getAsDouble());}
                p.setString(6,eventoId);p.executeUpdate();
            }
            try(var p=c.prepareStatement("SELECT id FROM leituras_alagamento WHERE evento_id=?")){p.setString(1,eventoId);try(var r=p.executeQuery()){if(!r.next())throw new SQLException("Evento nao persistido");leituraId=r.getLong(1);}}
            if("alagamento".equals("alagamento")) {
                try(var p=c.prepareStatement("UPDATE leituras_alagamento SET ponto_id=?,status=? WHERE evento_id=?")){p.setString(1,leitura.get("ponto_id").getAsString());p.setString(2,leitura.get("status").getAsString());p.setString(3,eventoId);p.executeUpdate();}
                for(var medicao:evento.getAsJsonArray("medicoes"))try(var p=c.prepareStatement("UPDATE medicoes_alagamento SET conjunto_id=? WHERE evento_id=?")){p.setString(1,eventoId);p.setString(2,medicao.getAsJsonObject().get("evento_id").getAsString());p.executeUpdate();}
            }
        }
        for(var item:evento.getAsJsonArray("alertas")) {
            var alerta=item.getAsJsonObject();
            try(var p=c.prepareStatement("INSERT OR IGNORE INTO alertas_alagamento(evento_id,leitura_id,tipo,mensagem,detalhes,criado_em) VALUES (?,?,?,?,?,?)")) {
                p.setString(1,alerta.get("evento_id").getAsString());if(leituraId==null)p.setNull(2,java.sql.Types.INTEGER);else p.setLong(2,leituraId);
                p.setString(3,alerta.get("tipo").getAsString());p.setString(4,alerta.get("mensagem").getAsString());p.setString(5,alerta.toString());p.setString(6,alerta.get("criado_em").getAsString());p.executeUpdate();
            }
        }
    }
}
