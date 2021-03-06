
package org.eljaiek.jmira.core.io;

import java.util.Observer;

/**
 *
 * @author eduardo.eljaiek
 */
public interface Download extends Runnable {
    
    int getSize();
    
    float getProgress();
    
    int getDownloaded();
    
    DownloadStatus getStatus();
    
    void pause();
    
    void resume();
    
    void cancel();
    
    void clean();
    
    void register(Observer observer);
}
