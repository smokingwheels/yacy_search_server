// Author: DL

package net.yacy.interaction;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import net.yacy.cora.document.UTF8;
import net.yacy.kelondro.logging.Log;
import net.yacy.search.Switchboard;

import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.util.FileManager;


public class TripleStore {

	public static Model model = ModelFactory.createDefaultModel();

	public static ConcurrentHashMap<String, Model> privatestorage = null;

	public static String file;


	public static void Load(String filename) throws IOException {
		if (filename.endsWith(".nt")) LoadNTriples(filename);
		else LoadRDF(filename);
	}

	public static void LoadRDF(String fileNameOrUri) throws IOException {
		Model tmp  = ModelFactory.createDefaultModel();
		Log.logInfo("TRIPLESTORE", "Loading from " + fileNameOrUri);
        InputStream is = FileManager.get().open(fileNameOrUri);
	    if (is != null) {
	    	// read the RDF/XML file
	    	tmp.read(is, null);
			Log.logInfo("TRIPLESTORE", "loaded " + tmp.size() + " triples from " + fileNameOrUri);
	    	model = model.union(tmp);
	    } else {
	        throw new IOException("cannot read " + fileNameOrUri);
	    }
	}

	public static void LoadNTriples(String fileNameOrUri) throws IOException {
	    Model tmp = ModelFactory.createDefaultModel();
		Log.logInfo("TRIPLESTORE", "Loading N-Triples from " + fileNameOrUri);
	    InputStream is = FileManager.get().open(fileNameOrUri);
	    if (is != null) {
	    	tmp.read(is, null, "N-TRIPLE");
			Log.logInfo("TRIPLESTORE", "loaded " + tmp.size() + " triples from " + fileNameOrUri);
        	model = model.union(tmp);
	        //model.write(System.out, "TURTLE");
	    } else {
	        throw new IOException("cannot read " + fileNameOrUri);
	    }
	}

	public static void Add (String rdffile) {

		Model tmp  = ModelFactory.createDefaultModel();


		try {
			InputStream in = new ByteArrayInputStream(UTF8.getBytes(rdffile));

            // read the RDF/XML file
            tmp.read(in, null);
        }
        finally
        {
            	model = model.union(tmp);
        }

	}

	public static void Save (String filename) {
		Log.logInfo("TRIPLESTORE", "Saving triplestore with " + model.size() + " triples to " + filename);
    	FileOutputStream fout;
		try {
			fout = new FileOutputStream(filename);
			model.write(fout);
			Log.logInfo("TRIPLESTORE", "Saved triplestore with " + model.size() + " triples to " + filename);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			Log.logWarning("TRIPLESTORE", "Saving to " + filename+" failed");
		}
	}


	public static void initPrivateStores(Switchboard switchboard) {

		Log.logInfo("TRIPLESTORE", "Init private stores");

		if (privatestorage != null) privatestorage.clear();

		try {

			Iterator<de.anomic.data.UserDB.Entry> it = switchboard.userDB.iterator(true);

			while (it.hasNext()) {
				de.anomic.data.UserDB.Entry e = it.next();
				String username = e.getUserName();

				Log.logInfo("TRIPLESTORE", "Init " + username);

				String filename = new File(switchboard.getConfig("dataRoot", ""), "DATA/TRIPLESTORE").toString()+"/"+username+"_triplestore.rdf";

				Model tmp  = ModelFactory.createDefaultModel();

				Log.logInfo("TRIPLESTORE", "Loading from " + filename);

				try {
		            InputStream in = FileManager.get().open(filename);

		            // read the RDF/XML file
		            tmp.read(in, null);
		        }
		        finally
		        {
		        	privatestorage.put(username, tmp);

		        }

			}

			}
			catch (Exception anyex) {

			}



		// create separate model

	}

	public static void savePrivateStores(Switchboard switchboard) {

		if (privatestorage == null) return;

		for (Entry<String, Model> s : privatestorage.entrySet()) {

			String filename = new File(switchboard.getConfig("dataRoot", ""), "DATA/TRIPLESTORE").toString()+"/"+s.getKey()+"_triplestore.rdf";

			FileOutputStream fout;
			try {


				fout = new FileOutputStream(filename);

				s.getValue().write(fout);

			} catch (Exception e) {
				// TODO Auto-generated catch block
				Log.logWarning("TRIPLESTORE", "Saving to " + filename+" failed");
			}

		}
	}

}