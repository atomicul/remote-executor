package dev.executor.sidecar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "sidecar", mixinStandardHelpOptions = true)
public class Main implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    @Option(names = "--dry-run", description = "Skip AWS API calls, log state changes to stdout")
    private boolean dryRun;

    @Parameters(index = "0", description = "gRPC server address (e.g. localhost:9090)")
    private String target;

    @Parameters(index = "1", defaultValue = "dev.executor.common.ShellService",
            description = "Fully qualified gRPC service name (default: ${DEFAULT-VALUE})")
    private String service;

    @Override
    public void run() {
        String mode = dryRun ? "Dry Run" : "Production";
        logger.info("Sidecar starting in {} mode, target={}, service={}", mode, target, service);
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
