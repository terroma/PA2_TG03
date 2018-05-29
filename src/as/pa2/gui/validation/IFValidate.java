/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package as.pa2.gui.validation;

/**
 *
 * @author bruno
 */
public interface IFValidate {
    
    
    public boolean validateIP(final String ip);
    
    public boolean validatePort(final String port);
    
    public boolean validateQueueSize(final String queueSize);
    
}
