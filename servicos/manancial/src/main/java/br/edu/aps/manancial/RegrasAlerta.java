package br.edu.aps.manancial;
import java.util.Map;

/** Limites didaticos configuraveis; nao representam criterios oficiais de emergencia. */
public final class RegrasAlerta {
    private RegrasAlerta() {}
    public static Map<String, Object> avaliar(Leitura leitura) {
        double baixo = limite("ALERTA_PERCENTUAL_BAIXO", 20, 0, 100);
        if (!(leitura.percentualOcupado() <= baixo)) return Map.of();
        return Map.of("tipo", "VOLUME_BAIXO", "mensagem", "Percentual ocupado abaixo ou igual ao limite de demonstracao.", "limites", Map.of("percentual_ocupado_max", baixo));
    }
    private static double limite(String nome, double padrao, double minimo, double maximo) {
        String valor = System.getenv(nome);
        double numero = valor == null ? padrao : Double.parseDouble(valor);
        if (!Double.isFinite(numero) || numero < minimo || numero > maximo)
            throw new IllegalArgumentException("Limite de alerta invalido: " + nome);
        return numero;
    }
}
