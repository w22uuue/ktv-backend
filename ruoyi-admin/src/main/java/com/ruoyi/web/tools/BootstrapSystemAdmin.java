package com.ruoyi.web.tools;

import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Arrays;
import org.springframework.security.crypto.bcrypt.BCrypt;

/** One-time initialization for the password-free public SQL seed; never resets an existing account. */
public final class BootstrapSystemAdmin
{
    private BootstrapSystemAdmin() { }

    public static void main(String[] args) throws Exception
    {
        String url = required("DB_URL");
        String user = required("DB_USER");
        String databasePassword = required("DB_PASSWORD");
        Console console = System.console();
        char[] entered = console == null ? required("RUOYI_BOOTSTRAP_ADMIN_PASSWORD").toCharArray()
                : console.readPassword("New PC administrator password (12+ characters): ");
        if (entered == null) throw new IllegalArgumentException("Password entry cancelled");
        try
        {
            String password = new String(entered);
            if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72)
                throw new IllegalArgumentException("Password must have at least 12 characters and at most 72 UTF-8 bytes");
            String hash = BCrypt.hashpw(password, BCrypt.gensalt(12));
            Class.forName("com.mysql.cj.jdbc.Driver");
            try (Connection connection = DriverManager.getConnection(url, user, databasePassword);
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE sys_user SET password=?,pwd_update_date=NOW() WHERE user_id=1 AND user_name='admin' AND password=''"))
            {
                statement.setString(1, hash);
                if (statement.executeUpdate() != 1)
                    throw new IllegalStateException("Initialization refused: import the empty seed first; existing passwords are never replaced");
            }
            System.out.println("PC administrator initialized. Sign in as admin with the password you supplied.");
        }
        finally
        {
            Arrays.fill(entered, '\0');
        }
    }

    private static String required(String name)
    {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException("Set environment variable " + name);
        return value;
    }
}
