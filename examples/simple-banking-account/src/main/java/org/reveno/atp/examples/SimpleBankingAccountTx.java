package org.reveno.atp.examples;

import org.reveno.atp.api.ChannelOptions;
import org.reveno.atp.api.Reveno;
import org.reveno.atp.api.commands.CommandContext;
import org.reveno.atp.api.transaction.TransactionContext;
import org.reveno.atp.core.Engine;
import org.reveno.atp.utils.MeasureUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Currency;

public class SimpleBankingAccountTx {

    protected static final Logger LOG = LoggerFactory.getLogger(SimpleBankingAccountTx.class);

    public static Reveno init(String folder) {
        Reveno reveno = new Engine(folder);
        reveno.config().journaling().channelOptions(ChannelOptions.BUFFERING_MMAP_OS).volumesSize(MeasureUtils.kb(128), MeasureUtils.kb(256));
        reveno.domain().command(AddToBalanceCommand.class, AddToBalanceCommand::handler);
        reveno.domain().command(CreateAccount.class, Long.class, CreateAccount::handler);
        reveno.domain().transactionAction(AddToBalance.class, AddToBalance::handler);
        reveno.domain().transactionAction(CreateAccount.class, CreateAccount::handler);
        reveno.domain().viewMapper(Account.class, AccountView.class, (id, e, r) -> new AccountView(id, e.name, e.balance));

        return reveno;
    }

    public static void main(String[] args) throws Exception {
        Files.list(new File(args[0]).toPath()).forEach(v -> {
            if (v.toFile().isFile()) {
                try {
                    Files.delete(v);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        Reveno reveno = init(args[0]);
        reveno.startup();

        long id = reveno.executeSync(new CreateAccount("John", Currency.getInstance("USD")));
        reveno.executeCommand(new AddToBalanceCommand(id, 10000, Currency.getInstance("USD"))).get();
        printStats(reveno, id);

        reveno.executeCommand(new AddToBalanceCommand(id, 1500, Currency.getInstance("USD"))).get();
        printStats(reveno, id);

        reveno.executeCommand(new AddToBalanceCommand(id, 1000, Currency.getInstance("USD"))).get();
        printStats(reveno, id);

        reveno.performCommands(Arrays.asList(new AddToBalanceCommand[] { new AddToBalanceCommand(id, 1000, Currency.getInstance("USD")), new AddToBalanceCommand(id, 1500, Currency.getInstance("USD")) })).get();
        printStats(reveno, id);

        System.in.read();

        reveno.shutdown();
        reveno = init(args[0]);
        reveno.startup();

        printStats(reveno, id);

        reveno.shutdown();
    }

    protected static void printStats(Reveno reveno, long id) {
        LOG.info("Balance of Account {}: {}", id, reveno.query().find(AccountView.class, id).balance);
    }

    public interface CurrencyConverter {
        long convert(Currency from, Currency to, long amount);
    }

    /**
     * In current example it pays role of both Command and Transaction Action.
     */
    public static class CreateAccount {
        public final String name;
        public final Currency currency;
        public long id;

        public CreateAccount(String name, Currency currency) {
            this.name = name;
            this.currency = currency;
        }

        /*
         * Command handler.
         *
         * Much better to replace this with DSL -> see SimpleBankingAccountDSL
         */
        public static long handler(CreateAccount cmd, CommandContext ctx) {
            cmd.id = ctx.id(Account.class);
            ctx.executeTxAction(cmd);

            return cmd.id;
        }

        /*
         * Transaction Action handler.
         *
         * Much better to replace this with DSL -> see SimpleBankingAccountDSL
         */
        public static void handler(CreateAccount tx, TransactionContext ctx) {
            ctx.repo().store(tx.id, new Account(tx.name, 0, tx.currency));
        }
    }

    public static class AddToBalanceCommand {
        protected static final CurrencyConverter converter = new DumbCurrencyConverter();
        public final long accountId;
        public final long amount;
        public final Currency currency;

        public AddToBalanceCommand(long accountId, long amount, Currency currency) {
            this.accountId = accountId;
            this.amount = amount;
            this.currency = currency;
        }

        public static void handler(AddToBalanceCommand cmd, CommandContext ctx) {
            if (!ctx.repo().has(Account.class, cmd.accountId)) {
                throw new RuntimeException(String.format("Account %s wasn't found!", cmd.accountId));
            }
            Account account = ctx.repo().get(Account.class, cmd.accountId);

            ctx.executeTxAction(new AddToBalance(cmd.accountId, converter.convert(cmd.currency, account.currency, cmd.amount)));
        }
    }

    public static class AddToBalance {
        public final long accountId;
        public final long amount;

        public AddToBalance(long accountId, long amount) {
            this.accountId = accountId;
            this.amount = amount;
        }

        public static void handler(AddToBalance tx, TransactionContext ctx) {
            ctx.repo().remap(tx.accountId, Account.class, (id, a) -> a.add(tx.amount));
            if (tx.amount == 1500) {
                throw new IllegalArgumentException("1500 Amount!");
            }
        }
    }

    public static class DumbCurrencyConverter implements CurrencyConverter {
        @Override
        public long convert(Currency from, Currency to, long amount) {
            if (from.getCurrencyCode().equals("USD") && to.getCurrencyCode().equals("EUR")) {
                return (long) (amount * 0.8822);
            }
            return amount;
        }
    }
}
