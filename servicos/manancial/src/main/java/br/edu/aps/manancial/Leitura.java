package br.edu.aps.manancial;

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
        String area,
        String timestamp,
        @SerializedName("percentual_ocupado") Double percentualOcupado,
        Double nivel_agua_m,
        Double temperatura_agua_c
) {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final Set<String> CAMPOS = Set.of("area", "timestamp", "percentual_ocupado", "nivel_agua_m", "temperatura_agua_c");

    public static Leitura deJson(String corpo) {
        JsonElement elemento;
        try {
            elemento = JSON.fromJson(corpo, JsonElement.class);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("JSON inválido: envie apenas um objeto com os cinco campos.");
        }
        if (elemento == null || !elemento.isJsonObject()) {
            throw new IllegalArgumentException("JSON inválido: envie um objeto com os cinco campos.");
        }
        JsonObject objeto = elemento.getAsJsonObject();
        for (String campo : objeto.keySet()) {
            if (!CAMPOS.contains(campo)) {
                throw new IllegalArgumentException("Campo desconhecido: " + campo);
            }
        }

        String identificacao = textoObrigatorio(objeto, "area");
        String momento = textoObrigatorio(objeto, "timestamp");
        JsonElement medida = objeto.get("percentual_ocupado");
        if (medida == null || medida.isJsonNull()) {
            throw new IllegalArgumentException("O campo percentual_ocupado é obrigatório e não pode ser null.");
        }
        if (!medida.isJsonPrimitive() || !medida.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("O campo percentual_ocupado deve ser um número.");
        }
        Leitura leitura = new Leitura(identificacao, momento, medida.getAsDouble(), numeroObrigatorio(objeto, "nivel_agua_m"), numeroObrigatorio(objeto, "temperatura_agua_c"));
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

    private static Double numeroObrigatorio(JsonObject objeto, String campo) {
        JsonElement valor = objeto.get(campo);
        if (valor == null || valor.isJsonNull()) {
            throw new IllegalArgumentException("O campo " + campo + " é obrigatório e não pode ser null.");
        }
        if (!valor.isJsonPrimitive() || !valor.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("O campo " + campo + " deve ser um número.");
        }
        return valor.getAsDouble();
    }

    private static void validarMedida(Double valor, String campo, double minimo) {
        if (valor == null || !Double.isFinite(valor) || valor < minimo) {
            throw new IllegalArgumentException("O campo " + campo
                    + " deve ser um número finito maior ou igual a " + minimo + ".");
        }
    }

    public void validar() {
        if (area == null || area.isBlank()) {
            throw new IllegalArgumentException("O campo area deve ser um texto não vazio.");
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
        if (percentualOcupado == null) {
            throw new IllegalArgumentException("O campo percentual_ocupado é obrigatório e não pode ser null.");
        }
        if (!Double.isFinite(percentualOcupado) || percentualOcupado < 0 || percentualOcupado > 100) {
            throw new IllegalArgumentException(
                    "percentual_ocupado deve ser um número entre 0 e 100, inclusive.");
        }
        validarMedida(nivel_agua_m, "nivel_agua_m", 0);
        validarMedida(temperatura_agua_c, "temperatura_agua_c", -273.15);
    }

    public String paraJson() {
        return JSON.toJson(this);
    }
}
