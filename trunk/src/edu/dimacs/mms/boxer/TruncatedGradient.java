package edu.dimacs.mms.boxer;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Vector;

import org.w3c.dom.Document;
import org.w3c.dom.Element;


/** Using Truncated Gradient for training of Polytomous Logistic
 * Regression Models (PLRM)
 */
public class TruncatedGradient extends PLRMLearner {

    static final boolean lazyT = true;

    /** Algorithm parameters */
    /** The learning rate eta. Default (as per paper) is 0.1. It was
	0.01 in the code used in January-Feb 2009 */
    double  eta=0.1;  
    /**  in Jan-Feb 2009 we had g=0.001 and K=1, i.e. truncation was
     *  applied at each step, but was very small. */
    double g=0.1;
    private int K = 10;
    
    Truncation commonTrunc; 

    class TruncatedGradientLearnerBlock extends PLRMLearner.PLRMLearnerBlock {

	Truncation trunc; 

	/** Creates a new learner block, possibly patterned on an old one
	    @param b An old block. If not null, this constructor will
	    copy all data structures of the old block into this block.
	    This can be used for AssumeDefaultHistory

	 */
	TruncatedGradientLearnerBlock(Discrimination _dis, TruncatedGradientLearnerBlock b) {
	    // synch any not-yet-applied truncation in b's matrix
	    if (b!=null) b.trunc.applyTruncationToAllRows(); 

	    if (Suite.verbosity>1) System.out.println("Creating TG block, based on " + (b==null ? "null" : "a non-null block" ));

	    // copy params
	    dis = _dis;
	    if (b==null) {
		trunc = new Truncation( commonTrunc, new Matrix[]{w});
	    } else {
		// copy b's matrices etc
		//System.out.println("TGLB( b=" + b+")");
		super.initFrom(b);
		// copy truncation and its state
		trunc = b.trunc.liveCopy( new Matrix[]{w} );
	    }
	}


	/** Incrementally train this classifier on data points xvec[i], i1&le; i &lt; i2
	    @param xvec A vector of data points. Elements from i1 through i2-1 will be used for this training sessions.
	    
	*/
	public void absorbExample(Vector<DataPoint> xvec, int i1, int i2) {
	    int d =  suite.getDic().getDimension(); // feature count
	    
	    if (Suite.verbosity>1) System.out.println("TruncatedGradient.absorbExample (dis="+dis.name+"):\n" +
			       "d="+d+"; eta=" + eta +"; "+ commonTrunc.describe());
       
	    for(int i=i1; i<i2; i++) {

		// 1. Acquire an example
		DataPoint x = xvec.elementAt(i);

		// 2. truncate (or, actually, request lazy truncation)

		trunc.requestTruncation(d);	    

		// 2(a). Actuate any postponed ("lazy evaluation") truncation that
		// needs to be done now because if affects the matrix's rows 
		// that we'll be using now
		for(int h=0; h<x.features.length; h++) {
		    int j = x.features[h];		
		    trunc.applyTruncation(j);
		}

		// 3. compute predictions
		double z[] = adjWeights(x);
		if (z==null) {
		    if (Suite.verbosity>1) System.out.println("Skip example " + x.name + " not labeled for " + dis.getName());
		    continue; // example not labeled for this discr
		}

		if (Suite.verbosity>1) {
		    // 4. output probability
		    System.out.print("z vector=[");
		    for(double q: z) System.out.print(" " + q);
		    System.out.println("]");
		    System.out.println("Multiplied by eta=" + eta);
		}

		// 6. update weights
		for(int h=0; h<x.features.length; h++) {
		    int j = x.features[h];	
		    w.addDenseRow(j, z, eta * x.values[h]);
		}
		// trunc.requestTruncation(d); // moved to bottom
		//System.out.println(describe());
	    }
	    trunc.applyTruncationToAllRows(); 
	}

	public void describe(PrintWriter out, boolean verbose) {
	    
	    out.println("===TruncatedGradient Classifier["+dis.name+"]===");
	    out.println(   commonTrunc.describe());
	    out.println("--- W --------------");
	    if (verbose) {
		w.describe( out, suite.getDic());
	    } else {
		out.println("(Matrix content skipped)");
	    }
	}

	public Element saveAsXML(Document xmldoc) {	    
 	    Element de =   xmldoc.createElement(XMLUtil.CLASSIFIER);

	    de.appendChild(createParamsElement
	    	 (xmldoc, 
		  new String[] { PARAM.t},
		  new double[] { trunc.t} ));
	    
	    de.setAttribute(XMLUtil.DISCRIMINATION, dis.getName());
	    de.appendChild(w.saveAsXML(xmldoc, dis, suite.getDic(), "W"));
	    return de;
	}

	HashMap<String, Matrix> listMatrices() {
	    HashMap<String, Matrix> h = new HashMap<String, Matrix>();
	    h.put("W", w);
	    return h;
	}

	public long memoryEstimate() {
	    return Sizeof.OBJ +  Sizeof.OBJREF + w.memoryEstimate();
	}
   
	/** Parses the element for discrimination-specific parameters,
	    which may be supplied in the learner's description. Also,
	    ensures that the block's Truncation instance is in sync with 
	    commonTrunc.

	    Overrides the method in the parent class
	*/    
	void parseDSP(Element e) {
	    // Make sure that we actually have a truncation instance
	    // in sync with the learner's "common" params. 2009-10-29
	    trunc = new Truncation( commonTrunc, new Matrix[]{w});

	    if (e==null) return;
	    XMLUtil.assertName(e, PARAMETERS);

	    HashMap<String,Object> h =
		parseParamsElement(e,new String[] {PARAM.t}, new double[] {0});
	    int t = ((Number)(h.get(PARAM.t))).intValue();
	    trunc.setT(t); // part of saved history
	}


    } // end of inner class

    public TruncatedGradient(Suite _suite) throws org.xml.sax.SAXException  {
	this(_suite, null);
    }

    /** Creates an instance of TruncatedGradient learner based on the
      content of an XML "learner" element. The element may be the
      top-level element of an XML file, or more often, an element
      nested within a "learners" element within a "learner complex"
      element.
      
    */
    TruncatedGradient(Suite _suite, Element e) throws org.xml.sax.SAXException {
	setSuite( _suite);
	commonTrunc = new Truncation( Param.INF, g*eta, K, new BetaMatrix[0], lazyT);
	if (e==null) {
	    createAllBlocks();
	} else {
	    parseLearner(e);
	}
    }

    public void describe(PrintWriter out, boolean verbose) {
	out.println("===" + algoName() + " Classifier===");
	out.println("eta="+ eta +", g=" + g);
	//+ "\n"+		   commonTrunc.describe());
	for(LearnerBlock b: blocks)  b.describe(out, verbose);
	out.println("=====================");
	out.println("[NET] Main tables memory estimate=" + memoryEstimate() + " bytes");
 	out.flush();
    }

    Element saveParamsAsXML(Document xmldoc) {
	return createParamsElement
	    (xmldoc, 
	     new String[] {"theta","eta","g", PARAM.K},
	     new Object[] {commonTrunc.reportTheta(),new Double(eta),
			   new Double(g),  new Integer(commonTrunc.K) });
    }

    void parseParams(Element e) {
	XMLUtil.assertName(e, Learner.PARAMETERS);

	Object otheta = Param.INF; // truncation with no threshold
	int t=0; // saved position in the truncation sequence

	HashMap<String,Object> h = makeHashMap
	    ( new String[] { "theta","eta","g",PARAM.K, PARAM.t},
	      new Object[] {otheta, new Double(eta), new Double(g), 
			    new Integer(K),  new Integer(0)});
	
	h = parseParamsElement(e,h);


	otheta = h.get("theta");
	eta =  ((Double)(h.get("eta"))).doubleValue();
	g =  ((Double)(h.get("g"))).doubleValue();
	K = ((Number)(h.get(PARAM.K))).intValue();
	t = ((Number)(h.get(PARAM.t))).intValue();
	commonTrunc = new Truncation(otheta,  K*g*eta, K, new BetaMatrix[0], lazyT);
	commonTrunc.setT(t); // part of saved history	
    }
    
       
    TruncatedGradientLearnerBlock createBlock(Discrimination dis, LearnerBlock model) {

	return new TruncatedGradientLearnerBlock(dis, (TruncatedGradientLearnerBlock) model);
    }



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