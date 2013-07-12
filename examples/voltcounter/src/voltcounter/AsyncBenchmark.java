/* This file is part of VoltDB.
 * Copyright (C) 2008-2013 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */
/*
 * This samples uses the native asynchronous request processing protocol
 * to post requests to the VoltDB server, thus leveraging to the maximum
 * VoltDB's ability to run requests in parallel on multiple database
 * partitions, and multiple servers.
 *
 * While asynchronous processing is (marginally) more convoluted to work
 * with and not adapted to all workloads, it is the preferred interaction
 * model to VoltDB as it guarantees blazing performance.
 *
 * Because there is a risk of 'firehosing' a database cluster (if the
 * cluster is too slow (slow or too few CPUs), this sample performs
 * auto-tuning to target a specific latency (5ms by default).
 * This tuning process, as demonstrated here, is important and should be
 * part of your pre-launch evaluation so you can adequately provision your
 * VoltDB cluster with the number of servers required for your needs.
 */

package voltcounter;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.voltdb.CLIConfig;
import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.ClientStats;
import org.voltdb.client.ClientStatsContext;
import org.voltdb.client.ClientStatusListenerExt;
import org.voltdb.client.NoConnectionsException;
import org.voltdb.client.NullCallback;
import org.voltdb.client.ProcCallException;
import org.voltdb.client.ProcedureCallback;

public class AsyncBenchmark {

    // handy, rather than typing this out several times
    static final String HORIZONTAL_RULE =
            "----------" + "----------" + "----------" + "----------" +
            "----------" + "----------" + "----------" + "----------" + "\n";

    // validated command line configuration
    final CounterConfig config;
    // Reference to the database connection we will use
    final Client client;
    // Timer for periodic stats printing
    Timer timer;
    // Benchmark start time
    long benchmarkStartTS;
    // Statistics manager objects from the client
    final ClientStatsContext periodicStatsContext;
    final ClientStatsContext fullStatsContext;

    // voter benchmark state
    AtomicLong countersIncremented = new AtomicLong(0);
    AtomicLong failedCountersIncremented = new AtomicLong(0);

    /**
     * Uses included {@link CLIConfig} class to declaratively state command line
     * options with defaults and validation.
     */
    static class CounterConfig extends CLIConfig {

        @CLIConfig.Option(desc = "Comma separated list of the form server[:port] to connect to.")
        String servers = "localhost";

        @CLIConfig.Option(desc = "Max Counter Classes")
        int maxcounterclass = 100;

        @CLIConfig.Option(desc = "Max Counter Per Counter Classe")
        int maxcounterperclass = 1000;

        @CLIConfig.Option(desc = "Max Counter Levels in a Class")
        int rolluptime = 30; // 30 Seconds;

        @CLIConfig.Option(desc = "Total Increment Transaction Per Thread")
        int incrementtimes = 1000;

        @Option(desc = "Interval for performance feedback, in seconds.")
        long displayinterval = 5;

        @Option(desc = "Benchmark duration, in seconds.")
        int duration = 20;

        @Option(desc = "Warmup duration in seconds.")
        int warmup = 2;
        
        @Option(desc = "Maximum TPS rate for benchmark.")
        int ratelimit = Integer.MAX_VALUE;

        @Option(desc = "Report latency for async benchmark run.")
        boolean latencyreport = true;

        @Option(desc = "Filename to write raw summary statistics to.")
        String statsfile = "";

        @Option(desc = "User name for connection.")
        String user = "";

        @Option(desc = "Password for connection.")
        String password = "";

        @CLIConfig.Option(desc = "Initialize Data?")
        boolean init = true;

        @Override
        public void validate() {
        }
    }

    /**
     * Provides a callback to be notified on node failure.
     * This example only logs the event.
     */
    class StatusListener extends ClientStatusListenerExt {
        @Override
        public void connectionLost(String hostname, int port, int connectionsLeft, DisconnectCause cause) {
            // if the benchmark is still active
            if ((System.currentTimeMillis() - benchmarkStartTS) < (config.duration * 1000)) {
                System.err.printf("Connection to %s:%d was lost.\n", hostname, port);
            }
        }
    }

    /**
     * Constructor for benchmark instance.
     * Configures VoltDB client and prints configuration.
     *
     * @param config Parsed & validated CLI options.
     */
    public AsyncBenchmark(CounterConfig config) {
        this.config = config;

        ClientConfig clientConfig = new ClientConfig(config.user, config.password, new StatusListener());
        clientConfig.setMaxTransactionsPerSecond(config.ratelimit);

        client = ClientFactory.createClient(clientConfig);

        periodicStatsContext = client.createStatsContext();
        fullStatsContext = client.createStatsContext();

        System.out.print(HORIZONTAL_RULE);
        System.out.println(" Command Line Configuration");
        System.out.println(HORIZONTAL_RULE);
        System.out.println(config.getConfigDumpString());
        if(config.latencyreport) {
            System.out.println("NOTICE: Option latencyreport is ON for async run, please set a reasonable ratelimit.\n");
        }
    }

    /**
     * Connect to a single server with retry. Limited exponential backoff.
     * No timeout. This will run until the process is killed if it's not
     * able to connect.
     *
     * @param server hostname:port or just hostname (hostname can be ip).
     */
    void connectToOneServerWithRetry(String server) {
        int sleep = 1000;
        while (true) {
            try {
                client.createConnection(server);
                break;
            }
            catch (Exception e) {
                System.err.printf("Connection failed - retrying in %d second(s).\n", sleep / 1000);
                try { Thread.sleep(sleep); } catch (Exception interruted) {}
                if (sleep < 8000) sleep += sleep;
            }
        }
        System.out.printf("Connected to VoltDB node at: %s.\n", server);
    }

    /**
     * Connect to a set of servers in parallel. Each will retry until
     * connection. This call will block until all have connected.
     *
     * @param servers A comma separated list of servers using the hostname:port
     * syntax (where :port is optional).
     * @throws InterruptedException if anything bad happens with the threads.
     */
    void connect(String servers) throws InterruptedException {
        System.out.println("Connecting to VoltDB...");

        String[] serverArray = servers.split(",");
        final CountDownLatch connections = new CountDownLatch(serverArray.length);

        // use a new thread to connect to each server
        for (final String server : serverArray) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    connectToOneServerWithRetry(server);
                    connections.countDown();
                }
            }).start();
        }
        // block until all have connected
        connections.await();
    }

    /**
     * Create a Timer task to display performance data on the Increment procedure
     * It calls printStatistics() every displayInterval seconds
     */
    public void schedulePeriodicStats() {
        timer = new Timer();
        TimerTask statsPrinting = new TimerTask() {
            @Override
            public void run() { printStatistics(); }
        };
        timer.scheduleAtFixedRate(statsPrinting,
                                  config.displayinterval * 1000,
                                  config.displayinterval * 1000);
    }

    /**
     * Prints a one line update on performance that can be printed
     * periodically during a benchmark.
     */
    public synchronized void printStatistics() {
        ClientStats stats = periodicStatsContext.fetchAndResetBaseline().getStats();
        long time = Math.round((stats.getEndTimestamp() - benchmarkStartTS) / 1000.0);

        System.out.printf("%02d:%02d:%02d ", time / 3600, (time / 60) % 60, time % 60);
        System.out.printf("Throughput %d/s, ", stats.getTxnThroughput());
        System.out.printf("Aborts/Failures %d/%d",
                stats.getInvocationAborts(), stats.getInvocationErrors());
        if(this.config.latencyreport) {
            System.out.printf(", Avg/95%% Latency %.2f/%dms", stats.getAverageLatency(),
                stats.kPercentileLatency(0.95));
        }
        System.out.printf("\n");
    }

    /**
     * Prints the results of the voting simulation and statistics
     * about performance.
     *
     * @throws Exception if anything unexpected happens.
     */
    public synchronized void printResults() throws Exception {
        ClientStats stats = fullStatsContext.fetch().getStats();

        // 1. Voting Board statistics, Voting results and performance statistics
        String display = "\n" +
                         HORIZONTAL_RULE +
                         " Counter Results\n" +
                         HORIZONTAL_RULE +
                         "\nA total of %d counters...\n" +
                         " - %,9d Incremented\n" +
                         " - %,9d Failed to Increment\n\n";
        System.out.printf(display, config.maxcounterclass * config.maxcounterperclass,
                countersIncremented.get(), failedCountersIncremented.get());


        // 3. Performance statistics
        System.out.print(HORIZONTAL_RULE);
        System.out.println(" Client Workload Statistics");
        System.out.println(HORIZONTAL_RULE);

        System.out.printf("Average throughput:            %,9d txns/sec\n", stats.getTxnThroughput());
        if(this.config.latencyreport) {
            System.out.printf("Average latency:               %,9.2f ms\n", stats.getAverageLatency());
            System.out.printf("10th percentile latency:       %,9d ms\n", stats.kPercentileLatency(.1));
            System.out.printf("25th percentile latency:       %,9d ms\n", stats.kPercentileLatency(.25));
            System.out.printf("50th percentile latency:       %,9d ms\n", stats.kPercentileLatency(.5));
            System.out.printf("75th percentile latency:       %,9d ms\n", stats.kPercentileLatency(.75));
            System.out.printf("90th percentile latency:       %,9d ms\n", stats.kPercentileLatency(.9));
            System.out.printf("95th percentile latency:       %,9d ms\n", stats.kPercentileLatency(.95));
            System.out.printf("99th percentile latency:       %,9d ms\n", stats.kPercentileLatency(.99));
            System.out.printf("99.5th percentile latency:     %,9d ms\n", stats.kPercentileLatency(.995));
            System.out.printf("99.9th percentile latency:     %,9d ms\n", stats.kPercentileLatency(.999));

            System.out.print("\n" + HORIZONTAL_RULE);
            System.out.println(" System Server Statistics");
            System.out.println(HORIZONTAL_RULE);
            System.out.printf("Reported Internal Avg Latency: %,9.2f ms\n", stats.getAverageInternalLatency());

            System.out.print("\n" + HORIZONTAL_RULE);
            System.out.println(" Latency Histogram");
            System.out.println(HORIZONTAL_RULE);
            System.out.println(stats.latencyHistoReport());
        }
        // 4. Write stats to file if requested
        client.writeSummaryCSV(stats, config.statsfile);
    }

    /**
     * Callback to handle the response to a stored procedure call.
     * Tracks response types.
     *
     */
    class CounterIncrementCallback implements ProcedureCallback {
        @Override
        public void clientCallback(ClientResponse response) throws Exception {
            if (response.getStatus() == ClientResponse.SUCCESS) {
                long cnt = response.getResults()[0].asScalarLong();
                countersIncremented.addAndGet(cnt);
            }
            else {
                countersIncremented.incrementAndGet();
            }
        }
    }

    /**
     * Core benchmark code.
     * Connect. Initialize. Run the loop. Cleanup. Print Results.
     *
     * @throws Exception if anything unexpected happens.
     */
    public void runBenchmark() throws Exception {
        System.out.print(HORIZONTAL_RULE);
        System.out.println(" Setup & Initialization");
        System.out.println(HORIZONTAL_RULE);

        // connect to one or more servers, loop until success
        connect(config.servers);

        System.out.print(HORIZONTAL_RULE);
        System.out.println(" Starting Benchmark");
        System.out.println(HORIZONTAL_RULE);

        if (config.init) {
            InitializeData();
        }

        // Run the benchmark loop for the requested warmup time
        // The throughput may be throttled depending on client configuration
        System.out.println("Warming up...");
        final long warmupEndTime = System.currentTimeMillis() + (1000l * config.warmup);
        while (warmupEndTime > System.currentTimeMillis()) {
            for (int i = 0; i < config.maxcounterclass * config.maxcounterperclass; i++) {
                for (int j = 0; j < config.incrementtimes; j++) {
                    try {
                        long counter_class_id = (i / config.maxcounterclass);
                        client.callProcedure(new CounterIncrementCallback(), "Increment", counter_class_id, i);

                        if (j % 1000 == 0) {
                            System.out.printf(".");
                        }
                    } catch (IOException ex) {
                        Logger.getLogger(AsyncBenchmark.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }
        }

        // reset the stats after warmup
        fullStatsContext.fetchAndResetBaseline();
        periodicStatsContext.fetchAndResetBaseline();

        // print periodic statistics to the console
        benchmarkStartTS = System.currentTimeMillis();
        schedulePeriodicStats();

        // Run the benchmark loop for the requested duration
        // The throughput may be throttled depending on client configuration
        System.out.println("\nRunning benchmark...");
        final long benchmarkEndTime = System.currentTimeMillis() + (1000l * config.duration);
        while (benchmarkEndTime > System.currentTimeMillis()) {
            for (int i = 0; i < config.maxcounterclass * config.maxcounterperclass; i++) {
                for (int j = 0; j < config.incrementtimes; j++) {
                    try {
                        long counter_class_id = (i / config.maxcounterclass);
                        client.callProcedure(new CounterIncrementCallback(), "Increment", counter_class_id, i);

                    } catch (IOException ex) {
                        Logger.getLogger(AsyncBenchmark.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }
        }

        // cancel periodic stats printing
        timer.cancel();

        // block until all outstanding txns return
        client.drain();

        // print the summary results
        printResults();

        // close down the client connections
        client.close();
    }

    /**
     * Load up data
     *
     * @throws IOException
     * @throws NoConnectionsException
     * @throws ProcCallException
     */
    public void InitializeData() throws IOException, NoConnectionsException, ProcCallException {
        int maxCounters = config.maxcounterclass * config.maxcounterperclass;
        ClientResponse cresponse =
                client.callProcedure("CleanCounters");
        if (cresponse.getStatus() != ClientResponse.SUCCESS) {
            throw new RuntimeException(cresponse.getStatusString());
        }
        for (int i = 0; i < config.maxcounterclass; i++) {
            client.callProcedure(new NullCallback(), "AddCounterClass", i);
        }
        // Add counters.
        long prev_cc = 0;
        for (int i = 0, par_idx = 0; i < maxCounters; i++, par_idx++) {
            long cc = (i / config.maxcounterperclass);
            boolean treeShift = false;
            if (prev_cc != cc) {
                par_idx = 0;
                prev_cc = cc;
                treeShift = true;
            } else {
                par_idx = i;
            }
            client.callProcedure(new NullCallback(), "AddCounter", cc, i,
                    "Counter-" + i, config.rolluptime, treeShift ? (par_idx - 1) : (i -1));
            if (treeShift) {
                par_idx = i;
            }
        }
    }

    /**
     * Main routine creates a benchmark instance and kicks off the run method.
     *
     * @param args Command line arguments.
     * @throws Exception if anything goes wrong.
     * @see {@link CounterConfig}
     */
    public static void main(String[] args) throws Exception {
        // create a configuration from the arguments
        CounterConfig config = new CounterConfig();
        config.parse(AsyncBenchmark.class.getName(), args);

        AsyncBenchmark benchmark = new AsyncBenchmark(config);
        benchmark.runBenchmark();
    }
}
