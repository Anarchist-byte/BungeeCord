package ru.leymooo.botfilter.utils;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.md_5.bungee.BungeeCord;
import ru.leymooo.botfilter.BotFilter;
import ru.leymooo.botfilter.utils.IPUtils;
import ru.leymooo.botfilter.config.Settings;
import ru.leymooo.botfilter.config.Settings.SQL;

/**
 *
 * @author Leymooo
 */
public class Sql
{

    private Connection connection;
    private final ExecutorService executor = Executors.newFixedThreadPool( 2, new ThreadFactoryBuilder().setNameFormat( "BotFilter-SQL-%d" ).build() );
    private final Logger logger = BungeeCord.getInstance().getLogger();
    private boolean connecting = false;

    public Sql()
    {
        setupConnect();
    }

    protected void setupConnect()
    {

        try
        {
            connecting = true;
            if ( connection != null && connection.isValid( 3 ) )
            {
                return;
            }
            logger.info( "[BotFilter] Подключаюсь к датабазе..." );
            long start = System.currentTimeMillis();
            if ( Settings.IMP.SQL.STORAGE_TYPE.equalsIgnoreCase( "mysql" ) )
            {
                SQL s = Settings.IMP.SQL;
                connectToDatabase( "JDBC:mysql://" + s.HOSTNAME + ":" + s.PORT + "/" + s.DATABASE, s.USER, s.PASSWORD );
            } else
            {
                Class.forName( "org.sqlite.JDBC" );
                connectToDatabase( "JDBC:sqlite:BotFilter/database.db", null, null );
            }
            logger.log( Level.INFO, "[BotFilter] Подключено ({0} мс)", System.currentTimeMillis() - start );
            createTable();
            clearOldUsers();
            loadUsers();
        } catch ( SQLException | ClassNotFoundException e )
        {
            logger.log( Level.WARNING, "Can not connect to database or execute sql: ", e );
            connection = null;
        } finally
        {
            connecting = false;
        }
    }

    protected void connectToDatabase(String url, String user, String password) throws SQLException
    {
        this.connection = DriverManager.getConnection( url, user, password );
    }

    protected void createTable() throws SQLException
    {
        String sql = "CREATE TABLE IF NOT EXISTS `Users` ("
                + "`Name` VARCHAR(16) NOT NULL PRIMARY KEY UNIQUE,"
                + "`Ip` VARCHAR(16) NOT NULL,"
                + "`LastCheck` BIGINT NOT NULL);";

        try ( PreparedStatement statement = connection.prepareStatement( sql ) )
        {
            statement.executeUpdate();
        }
    }

    protected void clearOldUsers() throws SQLException
    {
        Calendar calendar = Calendar.getInstance();
        calendar.add( Calendar.DATE, -Settings.IMP.SQL.PURGE_TIME );
        long until = calendar.getTimeInMillis();
        try ( PreparedStatement statement = connection.prepareStatement( "DELETE FROM `Users` WHERE `LastCheck` < " + until + ";" ) )
        {
            logger.log( Level.INFO, "[BotFilter] Очищено {0} аккаунтов", statement.executeUpdate() );
        }
    }

    protected void loadUsers() throws SQLException
    {
        try ( PreparedStatement statament = connection.prepareStatement( "SELECT * FROM `Users`;" );
                ResultSet set = statament.executeQuery() )
        {
            int i = 0;
            while ( set.next() )
            {
                i++;
                String name = set.getString( "Name" );
                String ip = set.getString( "Ip" );
                if ( isInvalidName( name ) )
                {
                    removeUser( "REMOVE FROM `Users` where `Ip` = '" + ip + "' and `LastCheck` = '" + set.getLong( "LastCheck" ) + "';" );
                }
                BotFilter.getInstance().saveUser( name, IPUtils.getAddress( ip ) );
            }
            logger.log( Level.INFO, "[BotFilter] Белый список игроков успешно загружен ({0})", i );
        }
    }

    private boolean isInvalidName(String name)
    {
        return name.contains( "'" ) || name.contains( "\"" );
    }

    private void removeUser(String sql)
    {
        if ( connection != null )
        {
            this.executor.execute( () ->
            {
                try ( PreparedStatement statament = connection.prepareStatement( sql ) )
                {
                    statament.execute();
                } catch ( SQLException ignored )
                {
                }
            } );
        }
    }

    public void saveUser(String name, String ip)
    {
        if ( connecting || isInvalidName( name ) )
        {
            return;
        }
        if ( connection != null )
        {
            this.executor.execute( () ->
            {
                final long timestamp = System.currentTimeMillis();
                String sql = "SELECT `Name` FROM `Users` where `Name` = '" + name + "';";
                try ( Statement statament = connection.createStatement();
                        ResultSet set = statament.executeQuery( sql ) )
                {
                    if ( !set.next() )
                    {
                        sql = "INSERT INTO `Users` (`Name`, `Ip`, `LastCheck`) VALUES ('" + name + "','" + ip + "','" + timestamp + "');";
                        statament.executeUpdate( sql );
                    } else
                    {
                        sql = "UPDATE `Users` SET `Ip` = '" + ip + "', `LastCheck` = '" + timestamp + "' where `Name` = '" + name + "';";
                        statament.executeUpdate( sql );
                    }
                } catch ( SQLException ex )
                {
                    logger.log( Level.WARNING, "Не могу выполнить запрос к базе данных", ex );
                    logger.log( Level.WARNING, sql );
                    executor.execute( () -> setupConnect() );
                }
            } );
        }
    }

    public void close()
    {
        this.executor.shutdownNow();
        try
        {
            if ( connection != null )
            {
                this.connection.close();
            }
        } catch ( SQLException ignore )
        {
        }
        this.connection = null;
    }
}
