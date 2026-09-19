package br.edu.aps.manancial;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/** Aceita somente campos conhecidos; valores sao sempre parametros SQL. */
public record Consulta(String where, List<Object> valores, int limite, int offset) {
    private static final List<String> MEDIDAS = List.of("percentual_ocupado", "nivel_agua_m", "temperatura_agua_c");

    public static Consulta deQuery(String query) { return deQuery(query, false); }
    public static Consulta deQuery(String query, boolean alertas) {
        Map<String, String> parametros = new LinkedHashMap<>();
        if (query != null && !query.isEmpty()) {
            for (String par : query.split("&", -1)) {
                String[] partes = par.split("=", 2);
                String nome = URLDecoder.decode(partes[0], StandardCharsets.UTF_8);
                String valor = partes.length == 2 ? URLDecoder.decode(partes[1], StandardCharsets.UTF_8) : "";
                if (valor.isBlank() || parametros.putIfAbsent(nome, valor) != null)
                    throw new IllegalArgumentException("Parametro vazio ou repetido: " + nome);
            }
        }
        Set<String> permitidos = new HashSet<>(List.of("sensor", "area", "data", "data_inicio", "data_fim", "limite", "offset"));
        for (String medida : MEDIDAS) {
            permitidos.add(medida);
            permitidos.add(medida + "_min");
            permitidos.add(medida + "_max");
        }
        if (alertas) permitidos.add("status_notificacao");
        for (String nome : parametros.keySet()) {
            if (!permitidos.contains(nome)) throw new IllegalArgumentException("Filtro desconhecido: " + nome);
        }
        List<String> condicoes = new ArrayList<>();
        List<Object> valores = new ArrayList<>();
        if (parametros.containsKey("status_notificacao")) {
            String status = parametros.get("status_notificacao");
            if (!java.util.Set.of("pendente", "enviado").contains(status))
                throw new IllegalArgumentException("status_notificacao deve ser pendente ou enviado.");
            adicionar(condicoes, valores, "status_notificacao = ?", status);
        }
        String identificador = parametros.get("area");
        if (!"sensor".equals("area") && identificador != null && parametros.containsKey("sensor"))
            throw new IllegalArgumentException("Use sensor ou area, nao ambos.");
        if (identificador == null) identificador = parametros.get("sensor");
        if (identificador != null) adicionar(condicoes, valores, "area = ?", identificador);
        if (parametros.containsKey("data")) {
            try {
                String data = parametros.get("data");
                if (!data.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException();
                LocalDate.parse(data);
                adicionar(condicoes, valores, "date(timestamp) = ?", data);
            } catch (RuntimeException e) { throw new IllegalArgumentException("data deve usar AAAA-MM-DD e uma data valida (UTC)."); }
        }
        OffsetDateTime inicio = instante(parametros.get("data_inicio"));
        OffsetDateTime fim = instante(parametros.get("data_fim"));
        if (inicio != null && fim != null && inicio.toInstant().isAfter(fim.toInstant()))
            throw new IllegalArgumentException("data_inicio deve ser anterior ou igual a data_fim.");
        if (inicio != null) adicionar(condicoes, valores, "julianday(timestamp) >= julianday(?)", inicio.toInstant().toString());
        if (fim != null) adicionar(condicoes, valores, "julianday(timestamp) <= julianday(?)", fim.toInstant().toString());
        for (String medida : MEDIDAS) {
            Double exato = numero(parametros.get(medida), medida);
            Double minimo = numero(parametros.get(medida + "_min"), medida + "_min");
            Double maximo = numero(parametros.get(medida + "_max"), medida + "_max");
            if (minimo != null && maximo != null && minimo > maximo)
                throw new IllegalArgumentException("Minimo maior que maximo: " + medida);
            if (exato != null && ((minimo != null && exato < minimo) || (maximo != null && exato > maximo)))
                throw new IllegalArgumentException("Valor exato fora do intervalo: " + medida);
            if (exato != null) adicionar(condicoes, valores, medida + " = ?", exato);
            if (minimo != null) adicionar(condicoes, valores, medida + " >= ?", minimo);
            if (maximo != null) adicionar(condicoes, valores, medida + " <= ?", maximo);
        }
        int limite = inteiro(parametros.getOrDefault("limite", "100"), "limite", 1, 1000);
        int offset = inteiro(parametros.getOrDefault("offset", "0"), "offset", 0, Integer.MAX_VALUE);
        return new Consulta(condicoes.isEmpty() ? "" : " WHERE " + String.join(" AND ", condicoes), List.copyOf(valores), limite, offset);
    }

    private static void adicionar(List<String> condicoes, List<Object> valores, String sql, Object valor) {
        condicoes.add(sql);
        valores.add(valor);
    }

    private static Double numero(String texto, String campo) {
        if (texto == null) return null;
        try {
            double valor = Double.parseDouble(texto);
            if (!Double.isFinite(valor)) throw new NumberFormatException();
            return valor;
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Numero invalido: " + campo); }
    }

    private static int inteiro(String texto, String campo, int minimo, int maximo) {
        try {
            int valor = Integer.parseInt(texto);
            if (valor < minimo || valor > maximo) throw new NumberFormatException();
            return valor;
        } catch (NumberFormatException e) { throw new IllegalArgumentException(campo + " deve ser inteiro entre " + minimo + " e " + maximo); }
    }

    private static OffsetDateTime instante(String texto) {
        if (texto == null) return null;
        try {
            if (!texto.matches("\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?([Zz]|[+-]\\d{2}:\\d{2})"))
                throw new IllegalArgumentException();
            return OffsetDateTime.parse(texto);
        } catch (RuntimeException e) { throw new IllegalArgumentException("Datas devem usar RFC 3339 com fuso, por exemplo 2026-09-18T12:00:00Z."); }
    }
}
