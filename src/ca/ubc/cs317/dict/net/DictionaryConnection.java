package ca.ubc.cs317.dict.net;

import ca.ubc.cs317.dict.model.Database;
import ca.ubc.cs317.dict.model.Definition;
import ca.ubc.cs317.dict.model.MatchingStrategy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.*;

/**
 * Created by Jonatan on 2017-09-09.
 */
public class DictionaryConnection {

    private static final int DEFAULT_PORT = 2628;
    private final PrintWriter out;
    private final Socket dictSocket;
    private final BufferedReader in;


    /** Establishes a new connection with a DICT server using an explicit host and port number, and handles initial
     * welcome messages.
     *
     * @param host Name of the host where the DICT server is running
     * @param port Port number used by the DICT server
     * @throws DictConnectionException If the host does not exist, the connection can't be established, or the messages
     * don't match their expected value.
     */
    public DictionaryConnection(String host, int port) throws DictConnectionException {
        try {
            // create connection with server
            dictSocket = new Socket(host, port);

            // write to and read from socket
            out = new PrintWriter(dictSocket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(dictSocket.getInputStream()));

            Status dictStatus = Status.readStatus(in);

            if (dictStatus.isNegativeReply()) {
                throw new DictConnectionException("Initial code is a transient or permanent failure");
            } else if (dictStatus.getStatusCode() != 220) {
                throw new DictConnectionException("Unexpected response code");
            }

        } catch (UnknownHostException e) {
            throw new DictConnectionException("Host does not exist");
        } catch (IOException e) {
            throw new DictConnectionException(e);
        }

    }

    /** Establishes a new connection with a DICT server using an explicit host, with the default DICT port number, and
     * handles initial welcome messages.
     *
     * @param host Name of the host where the DICT server is running
     * @throws DictConnectionException If the host does not exist, the connection can't be established, or the messages
     * don't match their expected value.
     */
    public DictionaryConnection(String host) throws DictConnectionException {
        this(host, DEFAULT_PORT);
    }

    /** Sends the final QUIT message and closes the connection with the server. This function ignores any exception that
     * may happen while sending the message, receiving its reply, or closing the connection.
     *
     */
    public synchronized void close() {
        try {
            out.println("QUIT");
            // receive message
            in.readLine();

            dictSocket.close();
        } catch (IOException e) {
            // do nothing
        }
    }

    /** Requests and retrieves all definitions for a specific word.
     *
     * @param word The word whose definition is to be retrieved.
     * @param database The database to be used to retrieve the definition. A special database may be specified,
     *                 indicating either that all regular databases should be used (database name '*'), or that only
     *                 definitions in the first database that has a definition for the word should be used
     *                 (database '!').
     * @return A collection of Definition objects containing all definitions returned by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Collection<Definition> getDefinitions(String word, Database database) throws DictConnectionException {
        Collection<Definition> set = new ArrayList<>();

        String dbName = database.getName();
        out.println("DEFINE " + dbName + " " + word);
        Status dictStatus = Status.readStatus(in);
        int statusCode = dictStatus.getStatusCode();

        if (statusCode == 550 || statusCode == 552 || statusCode == 250) {
            return set;
        } else if (statusCode != 150 && statusCode != 151) {
            throw new DictConnectionException("Unexpected response code");
        }

        // receives 150 n definitions retrieved message
        String[] statusString = DictStringParser.splitAtoms(dictStatus.getDetails());
        int numDefn = Integer.parseInt(statusString[0]);

        for(int i = 0; i < numDefn; i++) {
            try {
                // receive 151 word database name - text follows message
                String [] defnStatus = DictStringParser.splitAtoms(in.readLine());

                int defnStatusCode = Integer.parseInt(defnStatus[0]);
                if (defnStatusCode != 151) {
                    throw new DictConnectionException("Unexpected Status Code");
                }
                // get database name
                String db = defnStatus[2];

                // get definition
                String response;
                StringBuilder sb = new StringBuilder();
                Definition d = new Definition(word, db);
                while (!(response = in.readLine()).equals(".")) {
                    String[] line = DictStringParser.splitAtoms(response);
                    sb.append(String.join(" ", line));
                    sb.append("\n");
                }

                d.setDefinition(sb.toString());
                set.add(d);

            } catch (IOException e) {
                throw new DictConnectionException(e);
            }
        }

        Status endStatus= Status.readStatus(in);
        if (endStatus.getStatusCode() != 250) {
            throw new DictConnectionException("Expected status code 250");
        }



        return set;
    }

    /** Requests and retrieves a list of matches for a specific word pattern.
     *
     * @param word     The word whose definition is to be retrieved.
     * @param strategy The strategy to be used to retrieve the list of matches (e.g., prefix, exact).
     * @param database The database to be used to retrieve the definition. A special database may be specified,
     *                 indicating either that all regular databases should be used (database name '*'), or that only
     *                 matches in the first database that has a match for the word should be used (database '!').
     * @return A set of word matches returned by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Set<String> getMatchList(String word, MatchingStrategy strategy, Database database) throws DictConnectionException {
        Set<String> set = new LinkedHashSet<>();

        String dbName = database.getName();
        String stratName = strategy.getName();

        out.println("MATCH " + dbName + " " + stratName + " " + word);
        Status dictStatus = Status.readStatus(in);
        int statusCode = dictStatus.getStatusCode();

        if (statusCode == 550 || statusCode == 551 || statusCode == 552) {
            return set;
        } else if (statusCode != 152 && statusCode != 250) {
            throw new DictConnectionException("Unexpected response code");
        }

        String[] statusString = DictStringParser.splitAtoms(dictStatus.getDetails());
        int numWords = Integer.parseInt(statusString[0]);

        for(int i = 0; i < numWords; i++) {
            String[] line;
            try {
                line = DictStringParser.splitAtoms(in.readLine());
                String[] newArray = Arrays.copyOfRange(line, 1, line.length);
                set.add(String.join(" ", newArray));
            } catch (IOException e) {
                throw new DictConnectionException(e);
            }
        }

        try {
            in.readLine();
        } catch (IOException e) {
            throw new DictConnectionException(e);
        }
        Status endStatus= Status.readStatus(in);
        if (endStatus.getStatusCode() != 250) {
            throw new DictConnectionException("Expected status code 250");
        }

        return set;
    }

    /** Requests and retrieves a map of database name to an equivalent database object for all valid databases used in the server.
     *
     * @return A map of Database objects supported by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Map<String, Database> getDatabaseList() throws DictConnectionException {
        Map<String, Database> databaseMap = new HashMap<>();

        out.println("SHOW DB");
        Status dictStatus = Status.readStatus(in);
        int statusCode = dictStatus.getStatusCode();
        // check for expected responses
        if (statusCode == 554) {
            return databaseMap;
        } else if (statusCode != 110) {
            throw new DictConnectionException("Unexpected response code");
        }

        // find number of databases
        String[] statusString = DictStringParser.splitAtoms(dictStatus.getDetails());
        int dbs = Integer.parseInt(statusString[0]);

        // iterate through list of databases
        for(int i = 0; i < dbs; i++) {
            String[] line;
            try {
                line = DictStringParser.splitAtoms(in.readLine());
            } catch (IOException e) {
                throw new DictConnectionException(e);
            }
            String dbName = line[0];
            String dbDes = line[1];
            Database dbObj = new Database(dbName, dbDes);
            databaseMap.put(dbName, dbObj);
        }

        // check done
        try {
            in.readLine();
        } catch (IOException e) {
            throw new DictConnectionException(e);
        }
        Status endStatus= Status.readStatus(in);
        if (endStatus.getStatusCode() != 250) {
            throw new DictConnectionException("Expected status code 250");
        }

        return databaseMap;
    }

    /** Requests and retrieves a list of all valid matching strategies supported by the server.
     *
     * @return A set of MatchingStrategy objects supported by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Set<MatchingStrategy> getStrategyList() throws DictConnectionException {
        Set<MatchingStrategy> set = new LinkedHashSet<>();

        out.println("SHOW STRAT");
        Status dictStatus = Status.readStatus(in);
        int statusCode = dictStatus.getStatusCode();
        if (statusCode == 555) {
            return set;
        } else if (statusCode != 111) {
            throw new DictConnectionException("Unexpected response code");
        }

        // find number of strategies
        String[] statusString = DictStringParser.splitAtoms(dictStatus.getDetails());
        int strats = Integer.parseInt(statusString[0]);

        for(int i = 0; i < strats; i++) {
            String[] line;
            try {
                line = DictStringParser.splitAtoms(in.readLine());
            } catch (IOException e) {
                throw new DictConnectionException(e);
            }

            String stratName = line[0];
            String stratDes = line[1];
            MatchingStrategy strat = new MatchingStrategy(stratName, stratDes);
            set.add(strat);

        }

        try {
            in.readLine();
        } catch (IOException e) {
            throw new DictConnectionException(e);
        }
        Status endStatus= Status.readStatus(in);
        if (endStatus.getStatusCode() != 250) {
            throw new DictConnectionException("Expected status code 250");
        }

        return set;
    }

    /** Requests and retrieves detailed information about the currently selected database.
     *
     * @return A string containing the information returned by the server in response to a "SHOW INFO <db>" command.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized String getDatabaseInfo(Database d) throws DictConnectionException {
        StringBuilder sb = new StringBuilder();
        String dbName = d.getName();


        out.println("SHOW INFO " + dbName);
        Status dictStatus = Status.readStatus(in);
        int statusCode = dictStatus.getStatusCode();
        // check for expected responses
        if (statusCode == 550) {
            throw new DictConnectionException("Invalid database");
        } else if (statusCode != 112) {
            throw new DictConnectionException("Unexpected response code");
        }

        try {
            String response;
            while (!(response = in.readLine()).equals(".")) {
                String[] line = DictStringParser.splitAtoms(response);
                sb.append(String.join(" ", line));
                sb.append("\n");
            }

        } catch (IOException e) {
            throw new DictConnectionException(e);
        }

        try {
            in.readLine();
        } catch (IOException e){
            throw new DictConnectionException(e);
        }
//        System.out.println(sb);
        return sb.toString();
    }
}
