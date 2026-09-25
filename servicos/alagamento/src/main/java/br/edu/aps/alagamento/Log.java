package br.edu.aps.alagamento;
final class Log {
    static synchronized void info(String mensagem) {
        System.out.println("[" + java.time.LocalTime.now().withNano(0) + "] [alagamento] " + mensagem);
    }
}
