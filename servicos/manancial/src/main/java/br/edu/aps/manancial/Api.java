package br.edu.aps.manancial;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class Api {
    public static final int PORTA = Integer.parseInt(System.getenv().getOrDefault("APS_PORT", "8081"));
    private static final Gson JSON = new com.google.gson.GsonBuilder().serializeNulls().create();
    private static final String HOST = System.getenv().getOrDefault("APS_HOST", "127.0.0.1");

    public static void main(String[] args) throws IOException {
        HttpServer servidor = criarServidor(PORTA);
        Notificador notificador = new Notificador(new Banco(Banco.caminhoPadrao()));
        notificador.iniciar();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { notificador.close(); servidor.stop(0); }));
        servidor.start();
        System.out.println("API manancial: http://" + HOST + ":" + PORTA + "/leituras");
        System.out.println(".");
    }

    // Porta zero permite que os testes usem uma porta livre.
    public static HttpServer criarServidor(int porta) throws IOException {
        return criarServidor(porta, Banco.caminhoPadrao());
    }

    public static HttpServer criarServidor(int porta, java.nio.file.Path arquivo) throws IOException {
        Banco banco = new Banco(arquivo);
        HttpServer servidor = HttpServer.create(new InetSocketAddress(HOST, porta), 0);
        servidor.createContext("/", requisicao -> receber(requisicao, banco));
        return servidor;
    }

    private static void receber(HttpExchange requisicao, Banco banco) throws IOException {
        try {
            String rota = requisicao.getRequestURI().getPath();
            if ("/health/live".equals(rota) || "/health/ready".equals(rota)) {
                if (!"GET".equals(requisicao.getRequestMethod())) {
                    requisicao.getResponseHeaders().set("Allow", "GET");
                    responder(requisicao, 405, "erro", "Use GET para consultar a saude.");
                    return;
                }
                boolean pronto = "/health/live".equals(rota) || banco.pronto();
                responder(requisicao, pronto ? 200 : 503, "status", pronto ? "ok" : "indisponivel");
                return;
            }
            if ("/alertas".equals(rota)) {
                if (!"GET".equals(requisicao.getRequestMethod())) {
                    requisicao.getResponseHeaders().set("Allow", "GET");
                    responder(requisicao, 405, "erro", "Use GET para consultar alertas; eles sao gerados automaticamente por POST /leituras.");
                    return;
                }
                try {
                    responderJson(requisicao, 200, banco.consultarAlertas(Consulta.deQuery(requisicao.getRequestURI().getRawQuery(), true)));
                } catch (IllegalArgumentException e) {
                    responder(requisicao, 400, "erro", e.getMessage());
                } catch (java.sql.SQLException e) {
                    responder(requisicao, 500, "erro", "Nao foi possivel consultar os alertas.");
                }
                return;
            }
            if (!"/leituras".equals(requisicao.getRequestURI().getPath())) {
                responder(requisicao, 404, "erro", "Rota não encontrada; use /leituras.");
                return;
            }
            if ("GET".equals(requisicao.getRequestMethod())) {
                try {
                    Consulta consulta = Consulta.deQuery(requisicao.getRequestURI().getRawQuery());
                    responderJson(requisicao, 200, banco.consultar(consulta));
                } catch (IllegalArgumentException e) {
                    responder(requisicao, 400, "erro", e.getMessage());
                } catch (java.sql.SQLException e) {
                    System.err.println("Falha ao consultar leituras: " + e.getMessage());
                    responder(requisicao, 500, "erro", "Nao foi possivel consultar as leituras.");
                }
                return;
            }
            if (!"POST".equals(requisicao.getRequestMethod())) {
                requisicao.getResponseHeaders().set("Allow", "GET, POST");
                responder(requisicao, 405, "erro", "Use GET para consultar ou POST para enviar uma leitura.");
                return;
            }
            byte[] bytes = requisicao.getRequestBody().readNBytes(4097);
            if (bytes.length > 4096) {
                responder(requisicao, 400, "erro", "O corpo deve ter no máximo 4096 bytes.");
                return;
            }

            Leitura leitura;
            try {
                leitura = Leitura.deJson(new String(bytes, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                responder(requisicao, 400, "erro", e.getMessage());
                return;
            }

            try {
                var resultado = banco.salvar(leitura);
                System.out.println("Leitura salva: " + leitura.paraJson());
                if (!((java.util.List<?>)resultado.get("alertas")).isEmpty())
                    System.out.println("ALERTA: " + JSON.toJson(resultado.get("alertas")));
                responderJson(requisicao, 200, resultado);
            } catch (java.sql.SQLException e) {
                System.err.println("Falha ao salvar leitura: " + e.getMessage());
                responder(requisicao, 500, "erro", "Nao foi possivel salvar a leitura. Tente novamente.");
                return;
            }

        } finally {
            requisicao.close();
        }
    }

    private static void responder(HttpExchange requisicao, int status,
                                  String campo, String mensagem) throws IOException {
        responderJson(requisicao, status, Map.of(campo, mensagem));
    }

    private static void responderJson(HttpExchange requisicao, int status, Object dados) throws IOException {
        byte[] corpo = JSON.toJson(dados).getBytes(StandardCharsets.UTF_8);
        requisicao.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        requisicao.sendResponseHeaders(status, corpo.length);
        requisicao.getResponseBody().write(corpo);
    }
}
