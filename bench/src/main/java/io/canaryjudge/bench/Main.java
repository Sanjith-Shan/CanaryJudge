package io.canaryjudge.bench;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Experiment drivers. {@code bench <command> [--key value ...]}. */
public final class Main {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("commands: trials, evaluate, kayenta-diff, simulate, rollout");
            System.exit(2);
        }
        Map<String, String> a = parse(Arrays.copyOfRange(args, 1, args.length));
        switch (args[0]) {
            case "trials" -> TrialRunner.main(a);
            case "kayenta-diff" -> KayentaDiff.main(a);
            case "evaluate" -> Evaluate.main(a);
            case "simulate" -> Simulate.main(a);
            case "rollout" -> RolloutRunner.main(a);
            default -> {
                System.err.println("unknown command " + args[0]);
                System.exit(2);
            }
        }
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String k = args[i].substring(2);
            String v = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
            m.put(k, v);
        }
        return m;
    }
}
