package org.eljaiek.jmira.core.logs;

public interface LogHandler {

    void info(String log);
            
    void error(String log);
    
    void warn(String log);
}
