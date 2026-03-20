package chat.fold.cli;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

import java.util.Arrays;

@QuarkusMain
public class FoldMain {

    public static void main(String[] args) {
        if (args.length > 0 && "admin".equals(args[0])) {
            System.exit(AdminCli.run(Arrays.copyOfRange(args, 1, args.length)));
        }
        Quarkus.run(args);
    }
}
