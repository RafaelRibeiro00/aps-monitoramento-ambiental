package br.edu.aps.inversaotermica;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.google.gson.annotations.SerializedName;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;

// Um record é uma classe simples para transportar dados.
// Double (em vez de double) permite distinguir null de zero.
public record Leitura(
        String estacao,
        String timestamp,
        @SerializedName("umidade") Double umidade
) {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final Set<String> CAMPOS = Set.of("estacao", "timestamp", "umidade");

    public static Leitura deJson(String corpo) {
        JsonElement elemento;
        try {
            elemento = JSON.fromJson(corpo, JsonElement.class);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("JSON inválido: envie apenas um objeto com os três campos.");
        }
        if (elemento == null || !elemento.isJsonObject()) {
            throw new IllegalArgumentException("JSON inválido: envie um objeto com os três campos.");
        }
        JsonObject objeto = elemento.getAsJsonObject();
        for (String campo : objeto.keySet()) {
            if (!CAMPOS.contains(campo)) {
                throw new IllegalArgumentException("Campo desconhecido: " + campo);
            }
        }

        String identificacao = textoObrigatorio(objeto, "estacao");
        String momento = textoObrigatorio(objeto, "timestamp");
        JsonElement medida = objeto.get("umidade");
        if (medida == null || medida.isJsonNull()) {
            throw new IllegalArgumentException("O campo umidade é obrigatório e não pode ser null.");
        }
        if (!medida.isJsonPrimitive() || !medida.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("O campo umidade deve ser um número.");
        }
        Leitura leitura = new Leitura(identificacao, momento, medida.getAsDouble());
        leitura.validar();
        return leitura;
    }

    private static String textoObrigatorio(JsonObject objeto, String campo) {
        JsonElement valor = objeto.get(campo);
        if (valor == null || valor.isJsonNull() || !valor.isJsonPrimitive()
                || !valor.getAsJsonPrimitive().isString() || valor.getAsString().isBlank()) {
            throw new IllegalArgumentException("O campo " + campo + " deve ser um texto não vazio.");
        }
        return valor.getAsString();
    }

    public void validar() {
        if (estacao == null || estacao.isBlank()) {
            throw new IllegalArgumentException("O campo estacao deve ser um texto não vazio.");
        }
        try {
            // Exige segundos e fuso, conforme o formato RFC 3339 usado nesta etapa.
            if (timestamp == null || !timestamp.matches(
                    "\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?([Zz]|[+-]\\d{2}:\\d{2})")) {
                throw new DateTimeParseException("Formato inválido", "", 0);
            }
            OffsetDateTime.parse(timestamp);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "timestamp deve ser uma data/hora RFC 3339, por exemplo 2026-09-04T15:30:00Z.");
        }
        if (umidade == null) {
            throw new IllegalArgumentException("O campo umidade é obrigatório e não pode ser null.");
        }
        if (!Double.isFinite(umidade) || umidade < 0 || umidade > 100) {
            throw new IllegalArgumentException(
                    "umidade deve ser um número entre 0 e 100, inclusive.");
        }
    }

    public String paraJson() {
        return JSON.toJson(this);
    }
}
