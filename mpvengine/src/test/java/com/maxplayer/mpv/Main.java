package com.maxplayer.mpv;

public final class Main {
    public static void main(String[] args) throws Exception {
        boolean unit = args.length == 0 || java.util.Arrays.asList(args).contains("unit");
        boolean integ = args.length == 0 || java.util.Arrays.asList(args).contains("integration");
        if (unit) { System.out.println("== UNIT TESTS (JVM, fake backend) =="); UnitTests.run(); }
        if (integ) { System.out.println("\n== INTEGRATION TESTS (real desktop mpv via IPC; NOT Android) =="); IntegrationTests.run(); }
        System.out.printf("%n%d passed, %d failed, %d skipped%n", T.passed, T.failures.size(), T.skipped);
        for (String f : T.failures) System.out.println("FAILED: " + f);
        System.exit(T.failures.isEmpty() ? 0 : 1);
    }
}
