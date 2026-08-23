package org.atmkg.fixture;

import java.nio.file.Path;

public final class FixtureGeneratorMain {
    private FixtureGeneratorMain() {}

    public static void main(String[] args) {
        Path output = Path.of("fixtures/generated/small");
        FixtureScale scale = FixtureScale.SMALL;
        long seed = 20260821L;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--output": output = Path.of(requireValue(args, ++i, "--output")); break;
                case "--scale": scale = FixtureScale.parse(requireValue(args, ++i, "--scale")); break;
                case "--seed": seed = Long.parseLong(requireValue(args, ++i, "--seed")); break;
                default: throw new IllegalArgumentException("未知参数：" + args[i]);
            }
        }
        new FixtureDataGenerator().generate(output, scale, seed);
        System.out.println("fixture generated: " + output.toAbsolutePath());
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) throw new IllegalArgumentException(option + " 缺少值");
        return args[index];
    }
}
