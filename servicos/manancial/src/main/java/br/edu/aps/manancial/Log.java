package br.edu.aps.manancial;
final class Log {
    static synchronized void info(String mensagem) {
        System.out.println("[" + java.time.LocalTime.now().withNano(0) + "] [manancial] " + mensagem);
    }
}
