package edu.dimacs.mms.boxer;

import java.io.*;
//import java.util.Vector;
//import java.text.*;
import java.util.*;

import org.w3c.dom.Document;
import org.w3c.dom.Element;


/** This is a batch (<em>not</em> online) learner which "learns" by running BXRtrain, a program from the <a href="http://www.bayesianregression.com/bxr.html">BXR suite</a>. Scoring is done by calling BXRclassify. 
    
    <p>To interact with BXR

 */
public class BXRLearner extends Learner {

    double eps=1e-3;

    static private void captureStderr(BufferedReader er) {
	String line=null;
	try {
	    while ( (line = er.readLine()) != null) {
		synchronized(System.out) {
		    System.out.println("[BXR STDERR] " + line);
		}
	    }
	} catch(IOException ex) {
	    System.out.println("[BXR STDERR READ FAILED] " + ex.getMessage());
	}
    }

    class StderrThread extends Thread {
	BufferedReader er;
	StderrThread(Process p) {
	    er = new BufferedReader(new InputStreamReader(p.getErrorStream()));
	}
	public void run() {
	    captureStderr(er);
	}      
    }

    /** Reads output of "BXRclassify --classic", which looks like this:
	<pre>
	6 0.00039637625 0.0013176977 0.00023326283 0.00019617477 0.0011210707 0.00057918535 0.99476179 0.00025656682 0.00047311797 8.3813329e-05 0.00046581839 0.00011512403 6
	</pre>
     */

    class ReadScoresThread extends Thread {
	BufferedReader r;
	double [][] s;
	ReadScoresThread(Process p, double [][] _s) {
	   r = new BufferedReader(new InputStreamReader(p.getInputStream()));
	   s = _s;
	}
	public void run() {
	    String line=null;
	    try {
		for(int cnt=0; (line=r.readLine()) != null && cnt<s.length; cnt++)
		    {
			String[] tok=line.split("\\s+");
			s[cnt] = new double[tok.length-2];
			for(int i=0; i<s[cnt].length; i++) {
			    double d = (new Double(tok[i+1])).doubleValue();
			    final double M = -1000;
			    s[cnt][i] = (d<=0) ? M : Math.log(d);
			}
		    }
	    } catch(IOException ex) {
		System.out.println("[BXR STDERR READ FAILED] " + ex.getMessage());
	    }
	}      
    }
    

    final static String MODEL_SUFFIX = ".model";

    class BXRLearnerBlock extends Learner.LearnerBlock {

	BXRLearnerBlock(Discrimination _dis) {
	    dis = _dis;
	}

	boolean isZero() { return true; }


	String modelFileName = null;

	/** This is an alternative to absorbExample; it is used when
	 * we want to use a pre-existing model file.
	 */
	void setModelFile(String mo) {
	    if (modelFileName != null) {
		throw new IllegalArgumentException("Model file already set");
	    }
	    modelFileName = mo;
	    File f = new File(modelFileName);
	    if (!f.canRead()) throw new IllegalArgumentException("Model file "+modelFileName+" cannot be read");
	}

	/** Runs BXRtrain on the dataset specified. This method can
	 * only be used once, as no incrmental learning is supported
	 * with BXR. */
	public void absorbExample(Vector<DataPoint> xvec, int i1, int i2) 
	    throws  BoxerException {    
	    if (modelFileName!=null) throw new BoxerException("BXRLearner.absorbExample() can only be used once: no incremental training is supported.");
	    if (dis==suite.getFallback()) {
		Logging.info("BXRLearner: skipping training on the fallback discrimination");
		return;
	    }
	    try {
		String tmpFilePath = "/tmp/";

		File tmpDir = new File(tmpFilePath);
		if (!tmpDir.isDirectory() || !tmpDir.canWrite()) {
		    throw new BoxerException("Cannot write BXR model file to directory " + tmpFilePath);
		}

		long time = Calendar.getInstance().getTimeInMillis();
		modelFileName = tmpFilePath +  File.separatorChar  + 
		    dis.getName() + "_" + time + MODEL_SUFFIX;
		
		File modelFile = new File(modelFileName);
		if (modelFile.exists() && !modelFile.canWrite())  {
		    throw new BoxerException("Cannot re-write BXR model file " + modelFileName);
		}

		String[] cmd = {
		    "/usr/bin/time",
		    "BXRtrain", "-l", "0", "-e", ""+eps,  "-", modelFileName };
		String s = "";
		for(String x: cmd) s+=" " + x;
		Logging.info("Spawning process: {"+s+"} at t="+time+" ...");

		Process train = Runtime.getRuntime().exec(cmd);
		if (train==null) {
		    throw new BoxerException("exec BXRtrain failed");
		}


		// if the child process wanted to do a lot of writing before
		// it does any reading, we could get a deadlock (since 
		// we write first, and buffer sizes are finite), but
		// this ought not ot be an issue with BXRtrain
		PrintWriter out = new PrintWriter( new BufferedOutputStream(train.getOutputStream()));
		StderrThread seThread = new StderrThread(train);
		seThread.start();
		BufferedReader ir = new BufferedReader(new InputStreamReader(train.getInputStream()));
           
		final boolean numericCla=true;
		DataPoint.saveAsBMR(xvec, i1, i2, dis, numericCla, out);
		out.flush();
		out.close();

		String line = null;
		System.out.println("STDOUT <<");
		while ( (line = ir.readLine()) != null) {
		    System.out.println(line);
		}
		System.out.println(">>");

		try {
		    seThread.join();
		} catch (InterruptedException ex) {}

		int rv = train.waitFor();
		time = Calendar.getInstance().getTimeInMillis();
		Logging.info("BXRtrain completed at t="+time+", rv=" + rv);
		if (!modelFile.exists()) {
		    throw new BoxerException("BXRtrain failed to create model file " + modelFileName);
		}	
	    } catch (Exception ex) {
	    }
	}

	/** Not supported. */
	public double [] applyModel( DataPoint p) {
	    throw new UnsupportedOperationException("BXRLearner does not have single-datapoint eval; use vector eval instead");
	}

	/** Runs BXRclassify  on the stored model
	 */
	public double [][] applyModelLog( Vector<DataPoint> xvec, int i1, int i2) throws BoxerException {
	    
	    double[][] scores = new double[i2-i1][]; 
	    if (dis==suite.getFallback()) {
		// Just do the trivial dis here
		int disSize= dis.claCount();
		double logP = -Math.log(disSize);
		for(int i=0; i<i2-i1; i++) {
		    scores[i] = new double[disSize];
		    for(int j=0; j<disSize; j++) scores[i][j]=logP;
		}
		return scores;
	    }

	    String[] cmd = {
		"/usr/bin/time",
		"BXRclassify", "--classic", "-", modelFileName };
	    String s = "";
	    for(String x: cmd) s+=" " + x;
	    long time = Calendar.getInstance().getTimeInMillis();
	    Logging.info("Spawning process: {"+s+"} at t="+time+" ...");
	    
	    try {
		Process test = Runtime.getRuntime().exec(cmd);
		if (test==null) {
		    throw new BoxerException("exec BXRclassify failed");
		}
		

		PrintWriter out = new PrintWriter( new BufferedOutputStream(test.getOutputStream()));
		StderrThread seThread = new StderrThread(test);
		seThread.start();
	

		ReadScoresThread scoreThread= new ReadScoresThread(test,scores);
		scoreThread.start();
		
		final boolean numericCla=true;
		DataPoint.saveAsBMR(xvec, i1, i2, dis, numericCla, out);
		out.flush();
		out.close();
		
		try {
		    seThread.join();
		} catch (InterruptedException ex) {}
		try {
		    scoreThread.join();
		} catch (InterruptedException ex) {}
		
		int rv = test.waitFor();
		time = Calendar.getInstance().getTimeInMillis();
		Logging.info("BXRclassify completed at t="+time+", rv=" + rv);
		return scores;
	    } catch (Exception ex) {
		Logging.error("Error running BXRclassify: " + ex.getMessage());
		// FIXME: ought to throw something
		if (ex instanceof BoxerException) throw (BoxerException)ex;
		return null;
	    }
	}

	void parseDisc(Element e) {}
	
	public Element saveAsXML(Document xmldoc) {		
	    Element de =   xmldoc.createElement(XMLUtil.CLASSIFIER);
	    de.setAttribute(XMLUtil.DISCRIMINATION, dis.getName());
	    return de;
	}
	
	public long memoryEstimate() { return 0; }
    
    }
    

    public BXRLearner(Suite _suite, double _eps) {
	setSuite( _suite);
	eps = _eps;
	createAllBlocks();
    }
 
    public BXRLearner(Suite _suite, String modelfiles[], double _eps) {
	this( _suite, _eps);

	int nd = suite.disCnt();

	if (modelfiles.length != nd-1) throw new IllegalArgumentException("Number of BMR files passed to BXRLearner(...) should be the same as that of non-fallback discriminations!");

	for(String s: modelfiles) {
	    if (!s.endsWith(MODEL_SUFFIX))  throw new IllegalArgumentException("Model file name " + s +  " does not end in " + MODEL_SUFFIX);
	}


	for(int did=0; did<nd; did++) {
	    Discrimination dis = suite.getDisc(did);
	    if (dis==suite.getFallback()) continue;
	    String mo = null;
	    int moCnt=0;
	    for(String s: modelfiles) {
		String q = s.substring(0, s.length()-MODEL_SUFFIX.length());
		if (q.indexOf(dis.getName())>=0) {
		    mo = s;
		    moCnt++;
		}
	    }
	    if (mo==null) throw new IllegalArgumentException("No matching model file name for discrimination " + dis.getName());
	    if (moCnt>1) throw new IllegalArgumentException("Multiple matching model file names for discrimination " + dis.getName());

	    ((BXRLearnerBlock)blocks[did]).setModelFile(mo);
	}
    }

/** This overrides the method in the Learner class  */
public double [][] applyModelLog(Vector<DataPoint> v, int i0, int i1, int did)
throws BoxerException
 {
     return ((BXRLearnerBlock)blocks[did]).applyModelLog(v, i0, i1);
 }

    public void describe(PrintWriter out, boolean verbose) {
	//System.out.println("=== (S) BXRLearner Classifier===");
	out.println("===BXRLearner Classifier===");
	out.println("Has no parameters");
	out.println("Main tables memory estimate=" + memoryEstimate() + " bytes");
	out.println("===============================");
	out.flush();
    }


    /** Creates an instance of BXRLearner learner based on the
      content of an XML element (which may be the top-level element of
      an XML file), or more often, an element nested within a
      "learners" element within a "learner complex" element.
      
    */
    /*
    BXRLearner(Suite suite, Element e) throws
	org.xml.sax.SAXException, BoxerXMLException  {
	this(suite);
	XMLUtil.assertName(e, XMLUtil.LEARNER);
	initName(e);
	throw new AssertionError("Not supported yet");
    }
    */

    /** This is invoked from {@link Suite.deleteDiscrimination()},
     * before the discrimination is purged from the Suite. Child
     * classes may override it, to delete more structures.
     */
    void deleteDiscrimination( RenumMap map) {
    } 

    LearnerBlock createBlock(Discrimination dis, LearnerBlock model) {
	return new BXRLearnerBlock(dis);
    }

    /*
    public Element saveAsXML(Document xmldoc) {
		
	Element root = xmldoc.createElement( XMLUtil.LEARNER);
	root.setAttribute(ParseXML.ATTR.NAME_ATTR, algoName());
	root.setAttribute("version", Version.version);

	root.appendChild(createParamsElement(xmldoc, 
					     new String[] {},
					     new Object[] {}) );

	int disCnt =suite.did2discr.size();
       
	for(int did=0; did< disCnt; did++) {
	    Discrimination dis =  suite.did2discr.elementAt(did);
 	    Element de =   xmldoc.createElement(XMLUtil.CLASSIFIER);
	    de.setAttribute(XMLUtil.DISCRIMINATION, dis.getName());
	    root.appendChild(de);
	}
	return root;
    }
*/



}

/*
Copyright 2009, Rutgers University, New Brunswick, NJ.

All Rights Reserved

Permission to use, copy, and modify this software and its documentation for any purpose 
other than its incorporation into a commercial product is hereby granted without fee, 
provided that the above copyright notice appears in all copies and that both that 
copyright notice and this permission notice appear in supporting documentation, and that 
the names of Rutgers University, DIMACS, and the authors not be used in advertising or 
publicity pertaining to distribution of the software without specific, written prior 
permission.

RUTGERS UNIVERSITY, DIMACS, AND THE AUTHORS DISCLAIM ALL WARRANTIES WITH REGARD TO 
THIS SOFTWARE, INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR 
ANY PARTICULAR PURPOSE. IN NO EVENT SHALL RUTGERS UNIVERSITY, DIMACS, OR THE AUTHORS 
BE LIABLE FOR ANY SPECIAL, INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER 
RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, 
NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR 
PERFORMANCE OF THIS SOFTWARE.
*/

