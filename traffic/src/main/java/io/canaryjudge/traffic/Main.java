package io.canaryjudge.traffic;

import java.util.Arrays;

/** Entry point: {@code traffic splitter [options]} or {@code traffic loadgen [options]}. */
public final class Main {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: traffic splitter|loadgen [--key value ...]");
            System.exit(2);
        }
        Args a = Args.parse(Arrays.copyOfRange(args, 1, args.length));
        switch (args[0]) {
            case "splitter" -> Splitter.run(a);
            case "loadgen" -> LoadGen.run(a);
            default -> {
                System.err.println("unknown command " + args[0]);
                System.exit(2);
            }
        }
    }
}
