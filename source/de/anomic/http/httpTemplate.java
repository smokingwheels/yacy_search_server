// httpTemplate.java 
// -------------------------------------
// (C) by Michael Peter Christen; mc@anomic.de
// first published on http://www.anomic.de
// Frankfurt, Germany, 2004
// last major change: 16.01.2005
//
// $LastChangedDate$
// $LastChangedRevision$
// $LastChangedBy$
//
// extended for multi- and alternatives-templates by Alexander Schier
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
// Using this software in any meaning (reading, learning, copying, compiling,
// running) means that you agree that the Author(s) is (are) not responsible
// for cost, loss of data or any harm that may be caused directly or indirectly
// by usage of this softare or this documentation. The usage of this software
// is on your own risk. The installation and usage (starting/running) of this
// software may allow other people or application to access your computer and
// any attached devices and is highly dependent on the configuration of the
// software which must be done by the user of the software; the author(s) is
// (are) also not responsible for proper configuration and usage of the
// software, even if provoked by documentation provided together with
// the software.
//
// Any changes to this file according to the GPL as documented in the file
// gpl.txt aside this file in the shipment you received can be done to the
// lines that follows this copyright notice here, but changes must not be
// done inside the copyright notice above. A re-distribution must contain
// the intact and unchanged copyright notice.
// Contributions and changes to the program code must be marked as such.

package de.anomic.http;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;

import de.anomic.server.serverByteBuffer;
import de.anomic.server.serverFileUtils;
import de.anomic.server.logging.serverLog;

/**
 * A template engine, which substitutes patterns in strings<br>
 *
 * The template engine supports four types of templates:<br>
 * <ol>
 * <li>Normal templates: the template will be replaced by a string.</li>
 * <li>Multi templates: the template will be used more than one time.<br>
 * i.e. for lists</li>
 * <li>3. Alternatives: the program chooses one of multiple alternatives.</li>
 * <li>Includes: another file with templates will be included.</li>
 * </ol>
 *
 * All these templates can be used recursivly.<p>
 * <b>HTML-Example</b><br>
 * <pre>
 * &lt;html&gt;&lt;head&gt;&lt;/head&gt;&lt;body&gt;
 * #{times}#
 * Good #(daytime)#morning::evening#(/daytime)#, #[name]#!(#[num]#. Greeting)&lt;br&gt;
 * #{/times}#
 * &lt;/body&gt;&lt;/html&gt;
 * </pre>
 * <p>
 * The corresponding Hashtable to use this Template:<br>
 * <b>Java Example</b><br>
 * <pre>
 * Hashtable pattern;
 * pattern.put("times", 10); //10 greetings
 * for(int i=0;i<=9;i++){
 * 	pattern.put("times_"+i+"_daytime", 1); //index: 1, second Entry, evening
 * 	pattern.put("times_"+i+"_name", "John Connor");
 * 	pattern.put("times_"+i+"_num", (i+1));
 * }
 * </pre>
 * <p>
 * <b>Recursion</b><br>
 * If you use recursive templates, the templates will be used from
 * the external to the internal templates.
 * In our Example, the Template in #{times}##{/times}# will be repeated ten times.<br>
 * Then the inner Templates will be applied.
 * <p>
 * The inner templates have a prefix, so they may have the same name as a template on another level,
 * or templates which are in another recursive template.<br>
 * <b>The Prefixes:</b>
 * <ul>
 * <li>Multi templates: multitemplatename_index_</li>
 * <li>Alterantives: alternativename_</li>
 * </ul>
 * So the Names in the Hashtable are:
 * <ul>
 * <li>Multi templates: multitemplatename_index_templatename</li>
 * <li>Alterantives: alternativename_templatename</li>
 * </ul>
 * <i>#(alternative)#::#{repeat}##[test]##{/repeat}##(/alternative)#</i><br>
 * would be adressed as "alternative_repeat_"+number+"_test"
 */
public final class httpTemplate {
    
    public  static final byte hash = (byte)'#';
    private static final byte[] hasha = {hash};

    private static final byte dp = (byte)':';
    public  static final byte[] dpdpa = {dp, dp};

    private static final byte lbr  = (byte)'[';
    private static final byte rbr  = (byte)']';
    private static final byte[] pOpen  = {hash, lbr};
    private static final byte[] pClose = {rbr, hash};

    private static final byte lcbr  = (byte)'{';
    private static final byte rcbr  = (byte)'}';
    private static final byte[] mOpen  = {hash, lcbr};
    private static final byte[] mClose = {rcbr, hash};

    private static final byte lrbr  = (byte)'(';
    private static final byte rrbr  = (byte)')';
    private static final byte[] aOpen  = {hash, lrbr};
    private static final byte[] aClose = {rrbr, hash};

    private static final byte ps  = (byte)'%';
    private static final byte[] iOpen  = {hash, ps};
    private static final byte[] iClose = {ps, hash};

    public static final Object[] meta_quotation = new Object[] {
        new Object[] {pOpen, pClose},
        new Object[] {mOpen, mClose},
        new Object[] {aOpen, aClose},
        new Object[] {iOpen, iClose}
        };

    public static serverByteBuffer[] splitQuotations(serverByteBuffer text) {
        List l = splitQuotation(text, 0);
        serverByteBuffer[] sbbs = new serverByteBuffer[l.size()];
        for (int i = 0; i < l.size(); i++) sbbs[i] = (serverByteBuffer) l.get(i);
        return sbbs;
    }
    
    public static List splitQuotation(serverByteBuffer text, int qoff) {
        ArrayList l = new ArrayList();
        if (qoff >= meta_quotation.length) {
            if (text.length() > 0) l.add(text);
            return l;
        }
        int p = -1, q;
        byte[] left = (byte[]) ((Object[]) meta_quotation[qoff])[0];
        byte[] right = (byte[]) ((Object[]) meta_quotation[qoff])[1];
        qoff++;
        while ((text.length() > 0) && ((p = text.indexOf(left)) >= 0)) {
            q = text.indexOf(right, p + 1);
            if (q >= 0) {
                // found a pattern
                l.addAll(splitQuotation(new serverByteBuffer(text.getBytes(0, p)), qoff));
                l.add(new serverByteBuffer(text.getBytes(p, q + right.length)));
                text = new serverByteBuffer(text.getBytes(q + right.length));
            } else {
                // found only pattern start, no closing parantesis (a syntax error that is silently accepted here)
                l.addAll(splitQuotation(new serverByteBuffer(text.getBytes(0, p)), qoff));
                l.addAll(splitQuotation(new serverByteBuffer(text.getBytes(p)), qoff));
                text.clear();
            }
        }
        
        // find double-points
        while ((text.length() > 0) && ((p = text.indexOf(dpdpa)) >= 0)) {
            l.addAll(splitQuotation(new serverByteBuffer(text.getBytes(0, p)), qoff));
            l.add(new serverByteBuffer(dpdpa));
            l.addAll(splitQuotation(new serverByteBuffer(text.getBytes(p + 2)), qoff));
            text.clear();
        }
        
        // add remaining
        if (text.length() > 0) l.addAll(splitQuotation(text, qoff));
        return l;
    }
    
	/**
	 * transfer until a specified pattern is found; everything but the pattern is transfered so far
	 * the function returns true, if the pattern is found
	 */
    private static boolean transferUntil(PushbackInputStream i, OutputStream o, byte[] pattern) throws IOException {
        int b, bb;
        boolean equal;
        while ((b = i.read()) > 0) {
            if ((b & 0xFF) == pattern[0]) {
                // read the whole pattern
                equal = true;
                for (int n = 1; n < pattern.length; n++) {
                    if (((bb = i.read()) & 0xFF) != pattern[n]) {
                        // push back all
                        i.unread(bb);
                        equal = false;
                        for (int nn = n - 1; nn > 0; nn--) i.unread(pattern[nn]);
                        break;
                    }
                }
                if (equal) return true;
            }
            o.write(b);
        }
        return false;
    }
    
    public static String writeTemplate(InputStream in, OutputStream out, Hashtable pattern, byte[] dflt) throws IOException {
        return writeTemplate(in, out, pattern, dflt, "");
    }

	/**
	 * Reads a input stream, and writes the data with replaced templates on a output stream
	 */
    public static String writeTemplate(InputStream in, OutputStream out, Hashtable pattern, byte[] dflt, String prefix) throws IOException {
        PushbackInputStream pis = new PushbackInputStream(in, 100);
        ByteArrayOutputStream keyStream;
        String key;
        String multi_key;
        byte[] replacement;
        int bb;
        StringBuffer structure=new StringBuffer();
	
        while (transferUntil(pis, out, hasha)) {
	    bb = pis.read();
	    keyStream = new ByteArrayOutputStream();

	    if( (bb & 0xFF) == lcbr ){ //multi
		if( transferUntil(pis, keyStream, mClose) ){ //close tag
		    //multi_key =  "_" + keyStream.toString(); //for _Key
		    bb = pis.read();
		    if( (bb & 0xFF) != 10){ //kill newline
			pis.unread(bb);
		    }
		    multi_key = keyStream.toString(); //IMPORTANT: no prefix here
		    keyStream = new ByteArrayOutputStream(); //reset stream

			/* DEBUG - print key + value
			try{
				System.out.println("Key: "+prefix+multi_key+ "; Value: "+pattern.get(prefix+multi_key));
			}catch(NullPointerException e){
				System.out.println("Key: "+prefix+multi_key);
			}
			*/
			
		    //this needs multi_key without prefix
		    if( transferUntil(pis, keyStream, (new String(mOpen) + "/" + multi_key + new String(mClose)).getBytes()) ){
			bb = pis.read();
			if((bb & 0xFF) != 10){ //kill newline
			    pis.unread(bb);
			}

			String text=keyStream.toString(); //text between #{key}# an #{/key}#
			int num=0;
			if(pattern.containsKey(prefix+multi_key) && pattern.get(prefix+multi_key) != null){
			    try{
				num=Integer.parseInt((String)pattern.get(prefix+multi_key)); // Key contains the iteration number as string
			    }catch(NumberFormatException e){
				num=0;
			    }
			    //System.out.println(multi_key + ": " + num); //DEBUG
			}else{
			    //0 interations - no display
			    //System.out.println("_"+new String(multi_key)+" is null or does not exist"); //DEBUG
			}
			
                        //Enumeration enx = pattern.keys(); while (enx.hasMoreElements()) System.out.println("KEY=" + enx.nextElement()); // DEBUG
            structure.append("<"+multi_key+" type=\"multi\" num=\""+num+"\">\n");
			for(int i=0;i < num;i++ ){
                PushbackInputStream pis2 = new PushbackInputStream(new ByteArrayInputStream(text.getBytes()));
			    //System.out.println("recursing with text(prefix="+ multi_key + "_" + i + "_" +"):"); //DEBUG
			    //System.out.println(text);
			    structure.append(writeTemplate(pis2, out, pattern, dflt, prefix+multi_key + "_" + i + "_"));
			}//for
            structure.append("</"+multi_key+">\n");
		    }else{//transferUntil
				serverLog.logSevere("TEMPLATE", "No Close Key found for #{"+multi_key+"}#"); //prefix here?
			}
		}
	    }else if( (bb & 0xFF) == lrbr ){ //alternatives
		int others=0;
		String text="";
		PushbackInputStream pis2;
		
		transferUntil(pis, keyStream, aClose);
		key = keyStream.toString(); //Caution: Key does not contain prefix
		
		/* DEBUG - print key + value
		try{
			System.out.println("Key: "+prefix+key+ "; Value: "+pattern.get(prefix+key));
		}catch(NullPointerException e){
			System.out.println("Key: "+prefix+key);
		}
		*/

		keyStream=new ByteArrayOutputStream(); //clear
		
        boolean byName=false;
		int whichPattern=0;
        String patternName="";
		if(pattern.containsKey(prefix + key) && pattern.get(prefix + key) != null){
			String patternId=(String)pattern.get(prefix + key);
		    try{
    	        whichPattern=Integer.parseInt(patternId); //index
		    }catch(NumberFormatException e){
    			whichPattern=0;
				byName=true;
                patternName=patternId;
		    }
		}else{
		    //System.out.println("Pattern \""+new String(prefix + key)+"\" is not set"); //DEBUG
		}
		
		int currentPattern=0;
		boolean found=false;
		keyStream = new ByteArrayOutputStream(); //reset stream
        if(byName){
            //TODO: better Error Handling
            transferUntil(pis, keyStream, (new String("%%"+patternName)).getBytes());
    		if(pis.available()==0){
    			serverLog.logSevere("TEMPLATE", "No such Template: %%"+patternName);
                return structure.toString();
            }
            keyStream=new ByteArrayOutputStream();
            transferUntil(pis, keyStream, "::".getBytes());
            pis2 = new PushbackInputStream(new ByteArrayInputStream(keyStream.toString().getBytes()));
            structure.append(writeTemplate(pis2, out, pattern, dflt, prefix + key + "_"));
    		transferUntil(pis, keyStream, (new String("#(/"+key+")#")).getBytes());
    		if(pis.available()==0){
    			serverLog.logSevere("TEMPLATE", "No Close Key found for #("+key+")# (by Name)");
            }
        }else{
    		while(!found){
    		    bb=pis.read();
    		    if( (bb & 0xFF) == hash){
    			bb=pis.read();
    			if( (bb & 0xFF) == lrbr){
    			    transferUntil(pis, keyStream, aClose);
    
    				//reached the end. output last string.
    				if(keyStream.toString().equals("/" + key)){
    					pis2 = new PushbackInputStream(new ByteArrayInputStream(text.getBytes()));
    					//this maybe the wrong, but its the last
                        structure.append("<"+key+" type=\"alternative\" which=\""+whichPattern+"\" found=\"0\">\n");
    					structure.append(writeTemplate(pis2, out, pattern, dflt, prefix + key + "_"));
                        structure.append("</"+key+">\n");
    					found=true;
    				}else if(others >0 && keyStream.toString().startsWith("/")){ //close nested
    					others--;
    					text += "#("+keyStream.toString()+")#";
    				}else{ //nested
    					others++;
    					text += "#("+keyStream.toString()+")#";
    				}
    		    	keyStream = new ByteArrayOutputStream(); //reset stream
    				continue;
    			}else{ //is not #(
    				pis.unread(bb);//is processed in next loop
    				bb = (hash);//will be added to text this loop
    			    //text += "#";
    			}
    		    }else if( (bb & 0xFF) == ':' && others==0){//ignore :: in nested Expressions
    			bb=pis.read();
    			if( (bb & 0xFF) == ':'){
    			    if(currentPattern == whichPattern){ //found the pattern
    				pis2 = new PushbackInputStream(new ByteArrayInputStream(text.getBytes()));
                    structure.append("<"+key+" type=\"alternative\" which=\""+whichPattern+"\" found=\"0\">\n");
    				structure.append(writeTemplate(pis2, out, pattern, dflt, prefix + key + "_"));
                    structure.append("</"+key+">\n");
    
    				transferUntil(pis, keyStream, (new String("#(/"+key+")#")).getBytes());//to #(/key)#.
    
    				found=true;
    			    }
    			    currentPattern++;
    			    text="";
    			    continue;
    			}else{
    			    text += ":";
    			}
    		    }
    		    if(!found){
    			text += (char)bb;
    			if(pis.available()==0){
    				serverLog.logSevere("TEMPLATE", "No Close Key found for #("+key+")# (by Index)");
    				found=true;
    			}
    		    }
            }//while
        }//if(byName) (else branch)
	    }else if( (bb & 0xFF) == lbr ){ //normal
		if (transferUntil(pis, keyStream, pClose)) {
    	            // pattern detected, write replacement
		    key = keyStream.toString();
		    replacement = replacePattern(prefix+key, pattern, dflt); //replace
            structure.append("<"+key+" type=\"normal\">\n");
            structure.append(new String(replacement));
            structure.append("</"+key+">\n");

			/* DEBUG
			try{
				System.out.println("Key: "+key+ "; Value: "+pattern.get(key));
			}catch(NullPointerException e){
				System.out.println("Key: "+key);
			}
			*/

		    serverFileUtils.write(replacement, out);
    	        } else {
		    // inconsistency, simply finalize this
		    serverFileUtils.copy(pis, out);
            	    return structure.toString();
		}
	    }else if( (bb & 0xFF) == ps){ //include
			String include = "";
			String line = "";
                        keyStream = new ByteArrayOutputStream(); //reset stream
			if(transferUntil(pis, keyStream, iClose)){
			String filename = keyStream.toString();
			if(filename.startsWith( Character.toString((char)lbr) ) && filename.endsWith( Character.toString((char)rbr) )){ //simple pattern for filename
				filename= new String(replacePattern( prefix + filename.substring(1, filename.length()-1), pattern, dflt));
			}
            if (!filename.equals("") && !java.util.Arrays.equals(filename.getBytes(), dflt)) {
                BufferedReader br = null;
				try{
					//br = new BufferedReader(new InputStreamReader(new FileInputStream( filename ))); //Simple Include
					br = new BufferedReader( new InputStreamReader(new FileInputStream( httpdFileHandler.getLocalizedFile(filename) )) ); //YaCy (with Locales)
					//Read the Include
					while( (line = br.readLine()) != null ){
						include+=line+de.anomic.server.serverCore.crlfString;
					}
				}catch(IOException e){
					//file not found?                    
					serverLog.logSevere("FILEHANDLER","Include Error with file: "+filename);
				} finally {
                    if (br!=null) try{br.close(); br=null;}catch(Exception e){}
                }
				PushbackInputStream pis2 = new PushbackInputStream(new ByteArrayInputStream(include.getBytes()));
                structure.append("<fileinclude file=\""+filename+">\n");
				structure.append(writeTemplate(pis2, out, pattern, dflt, prefix));
                structure.append("</fileinclude>\n");
			}
            }
		}else{ //no match, but a single hash (output # + bb)
		byte[] tmp=new byte[2];
		tmp[0]=hash;
		tmp[1]=(byte)bb;
		serverFileUtils.write(tmp, out);
	    }
        }
        //System.out.println(structure.toString()); //DEBUG
        return structure.toString();
    }

    public static byte[] replacePattern(String key, Hashtable pattern, byte dflt[]) {
        byte[] replacement;
        Object value;
        if (pattern.containsKey(key)) {
            value = pattern.get(key);
            try {
                if (value instanceof byte[]) {
                    replacement = (byte[]) value;
                } else if (value instanceof String) {
                    replacement = ((String) value).getBytes("UTF-8");
                } else {
                    replacement = value.toString().getBytes("UTF-8");
                }
            } catch (UnsupportedEncodingException e) {
                replacement = dflt;
            }
        } else {
            replacement = dflt;
        }
        return replacement;
    }

    public static void main(String[] args) {
        // arg1 = test input; arg2 = replacement for pattern 'test'; arg3 = default replacement
        try {
            InputStream i = new ByteArrayInputStream(args[0].getBytes());
            Hashtable h = new Hashtable();
            h.put("test", args[1].getBytes());
            writeTemplate(new PushbackInputStream(i, 100), System.out, h, args[2].getBytes());
            System.out.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    /*
     * loads all Files from path into a filename->content HashMap
     */
    public static HashMap loadTemplates(File path) {
        // reads all templates from a path
        // we use only the folder from the given file path
        HashMap result = new HashMap();
        if (path == null) return result;
        if (!(path.isDirectory())) path = path.getParentFile();
        if ((path == null) || (!(path.isDirectory()))) return result;
        String[] templates = path.list();
        for (int i = 0; i < templates.length; i++) {
            if (templates[i].endsWith(".template")) 
                try {
                    //System.out.println("TEMPLATE " + templates[i].substring(0, templates[i].length() - 9) + ": " + new String(buf, 0, c));
                    result.put(templates[i].substring(0, templates[i].length() - 9),
                            new String(serverFileUtils.read(new File(path, templates[i]))));
                } catch (Exception e) {}
        }
        return result;
    }
    
}
