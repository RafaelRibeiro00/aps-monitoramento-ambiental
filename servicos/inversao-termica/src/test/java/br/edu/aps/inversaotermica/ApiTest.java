package br.edu.aps.inversaotermica;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ApiTest {
    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path temporario;
    private static HttpServer servidor;
    private static URI destino;
    private static final HttpClient CLIENTE = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static final String VALIDO =
            "{\"estacao\":\"ESTACAO-001\",\"timestamp\":\"2026-09-04T15:30:00Z\",\"umidade\":68.5,\"temperatura_c\":24.5,\"vento_km_h\":8.2}";

    @BeforeAll
    static void iniciar() throws Exception {
        servidor = Api.criarServidor(0, temporario.resolve("api.db"));
        servidor.start();
        destino = URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + "/leituras");
    }

    @AfterAll
    static void encerrar() {
        if (servidor != null) servidor.stop(0);
    }

    static Stream<Arguments> leituras() {
        return Stream.of(
                Arguments.of("válida", VALIDO, 200, "sucesso"),
                Arguments.of("zero válido", VALIDO.replace("68.5", "0"), 200, "sucesso"),
                Arguments.of("limite 100", VALIDO.replace("68.5", "100"), 200, "sucesso"),
                Arguments.of("fuso horário", VALIDO.replace("15:30:00Z", "12:30:00-03:00"), 200, "sucesso"),
                Arguments.of("fração de segundo", VALIDO.replace("00Z", "00.123456789Z"), 200, "sucesso"),
                Arguments.of("medida ausente", VALIDO.replace(",\"umidade\":68.5", ""), 400, "umidade"),
                Arguments.of("medida null", VALIDO.replace("68.5", "null"), 400, "umidade"),
                Arguments.of("negativo", VALIDO.replace("68.5", "-0.1"), 400, "umidade"),
                Arguments.of("medida como texto", VALIDO.replace("68.5", "\"68.5\""), 400, "umidade"),
                Arguments.of("medida booleana", VALIDO.replace("68.5", "true"), 400, "umidade"),
                Arguments.of("medida objeto", VALIDO.replace("68.5", "{}"), 400, "umidade"),
                Arguments.of("medida enorme", VALIDO.replace("68.5", "1e999"), 400, "umidade"),
                Arguments.of("identificador ausente", VALIDO.replace("\"estacao\":\"ESTACAO-001\",", ""), 400, "estacao"),
                Arguments.of("identificador vazio", VALIDO.replace("ESTACAO-001", "  "), 400, "estacao"),
                Arguments.of("identificador numérico", VALIDO.replace("\"ESTACAO-001\"", "123"), 400, "estacao"),
                Arguments.of("identificador null", VALIDO.replace("\"ESTACAO-001\"", "null"), 400, "estacao"),
                Arguments.of("timestamp ausente", VALIDO.replace("\"timestamp\":\"2026-09-04T15:30:00Z\",", ""), 400, "timestamp"),
                Arguments.of("timestamp numérico", VALIDO.replace("\"2026-09-04T15:30:00Z\"", "123"), 400, "timestamp"),
                Arguments.of("timestamp null", VALIDO.replace("\"2026-09-04T15:30:00Z\"", "null"), 400, "timestamp"),
                Arguments.of("data inválida", VALIDO.replace("2026-09-04T15:30:00Z", "04/09/2026"), 400, "RFC 3339"),
                Arguments.of("dia inexistente", VALIDO.replace("2026-09-04", "2026-02-30"), 400, "RFC 3339"),
                Arguments.of("sem fuso", VALIDO.replace("00Z", "00"), 400, "RFC 3339"),
                Arguments.of("sem segundos", VALIDO.replace("15:30:00Z", "15:30Z"), 400, "RFC 3339"),
                Arguments.of("campo extra", VALIDO.replace("}", ",\"extra\":1}"), 400, "extra"),
                Arguments.of("vazio", "", 400, "JSON"),
                Arguments.of("objeto vazio", "{}", 400, "estacao"),
                Arguments.of("sintaxe inválida", "{", 400, "JSON"),
                Arguments.of("aspas simples", VALIDO.replace('"', '\''), 400, "JSON"),
                Arguments.of("comentário", "/* comentário */" + VALIDO, 400, "JSON"),
                Arguments.of("null", "null", 400, "JSON"),
                Arguments.of("lista", "[]", 400, "JSON"),
                Arguments.of("dois objetos", VALIDO + VALIDO, 400, "JSON"),
                Arguments.of("conteúdo posterior", VALIDO + " lixo", 400, "JSON"),
                Arguments.of("corpo excessivo", " ".repeat(4097), 400, "4096"),
                Arguments.of("acima de 100", VALIDO.replace("68.5", "100.1"), 400, "umidade")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("leituras")
    void validarIngestao(String nome, String corpo, int status, String trecho) throws Exception {
        HttpResponse<String> resposta = enviar("POST", destino, corpo);
        assertEquals(status, resposta.statusCode(), resposta.body());
        assertTrue(resposta.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        String campo = status == 200 ? "mensagem" : "erro";
        String mensagem = JsonParser.parseString(resposta.body()).getAsJsonObject().get(campo).getAsString();
        assertTrue(mensagem.contains(trecho), mensagem);
    }

    @Test
    void rejeitarMetodoERota() throws Exception {
        HttpResponse<String> metodo = enviar("DELETE", destino, "");
        assertEquals(405, metodo.statusCode());
        assertEquals("GET, POST", metodo.headers().firstValue("Allow").orElse(""));
        assertEquals(404, enviar("POST", destino.resolve("/outra"), VALIDO).statusCode());
    }

    private static HttpResponse<String> enviar(String metodo, URI url, String corpo) throws Exception {
        HttpRequest requisicao = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .method(metodo, HttpRequest.BodyPublishers.ofString(corpo))
                .build();
        return CLIENTE.send(requisicao, HttpResponse.BodyHandlers.ofString());
    }
}
