package ahrd.controller;

import static ahrd.controller.Settings.getSettings;
import static ahrd.controller.Settings.setSettings;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import nu.xom.ParsingException;

import org.xml.sax.SAXException;

import ahrd.exception.MissingAccessionException;
import ahrd.exception.MissingInterproResultException;
import ahrd.exception.MissingProteinException;
import ahrd.model.BlastResult;
import ahrd.model.GeneOntologyResult;
import ahrd.model.InterproResult;
import ahrd.model.Protein;
import ahrd.view.OutputWriter;

public class AHRD {

	public static final String VERSION = "2.0";

	private Map<String, Protein> proteins;
	private Map<String, Double> descriptionScoreBitScoreWeights = new HashMap<String, Double>();
	private long timestamp;
	private long memorystamp;

	protected long takeTime() {
		// Measure time:
		long now = (new Date()).getTime();
		long measuredSeconds = (now - this.timestamp) / 1000;
		this.timestamp = now;
		return measuredSeconds;
	}

	protected long takeMemoryUsage() {
		// Measure Memory-Usage:
		Runtime rt = Runtime.getRuntime();
		this.memorystamp = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
		return this.memorystamp;
	}

	public static void main(String[] args) {
		System.out.println("Usage:\njava -Xmx2g -jar ahrd.jar input.yml\n");

		try {
			AHRD ahrd = new AHRD(args[0]);
			// Load and parse all inputs
			ahrd.setup(true);
			// Iterate over all Proteins and assign the best scoring Human
			// Readable Description
			ahrd.assignHumanReadableDescriptions();
			// Log
			System.out
					.println("...assigned highestest scoring human readable descriptions in "
							+ ahrd.takeTime()
							+ "sec, currently occupying "
							+ ahrd.takeMemoryUsage() + " MB");
			// Write result to output-file:
			System.out.println("Writing output to '"
					+ getSettings().getPathToOutput() + "'.");
			OutputWriter ow = new OutputWriter(ahrd.getProteins().values());
			ow.writeOutput();
			// Log
			System.out.println("Wrote output in " + ahrd.takeTime()
					+ "sec, currently occupying " + ahrd.takeMemoryUsage()
					+ " MB\n\nDONE");
		} catch (Exception e) {
			System.err.println("We are sorry, an un-expected ERROR occurred:");
			e.printStackTrace(System.err);
		}
	}

	/**
	 * Constructor initializes this run's settings as a thread-local variable.
	 * 
	 * @param pathToYmlInput
	 * @throws IOException
	 */
	public AHRD(String pathToYmlInput) throws IOException {
		super();
		setSettings(new Settings(pathToYmlInput));
	}

	public void initializeProteins() throws IOException,
			MissingAccessionException {
		setProteins(Protein
				.initializeProteins(getSettings().getProteinsFasta()));
	}

	public void parseBlastResults() throws IOException,
			MissingProteinException, SAXException {
		for (String blastDatabase : getSettings().getBlastDatabases()) {
			// Last Argument 'false' means that the best scoring BlastHits are
			// not remembered to be compared with the AHRD-Result, as would be
			// done for training the algorithm:
			BlastResult.parseBlastResults(getProteins(), blastDatabase);
		}
	}

	public void parseInterproResult() throws IOException {
		if (getSettings().hasInterproAnnotations()) {
			Set<String> missingProteinAccessions = new HashSet<String>();
			try {
				InterproResult.parseInterproResult(proteins);
			} catch (MissingProteinException mpe) {
				missingProteinAccessions.add(mpe.getMessage());
			}
			if (missingProteinAccessions.size() > 0) {
				System.err
						.println("WARNING - The following Gene-Accessions were referenced in the Interpro-Result-File,"
								+ " but could not be found in the Memory-Protein-Database:\n"
								+ missingProteinAccessions);
			}
		}
	}

	public void parseGeneOntologyResult() throws IOException {
		if (getSettings().hasGeneOntologyAnnotations()) {
			GeneOntologyResult.parseGeneOntologyResult(getProteins());
		}
	}

	public void filterBestScoringBlastResults(Protein prot) {
		for (String blastDatabaseName : prot.getBlastResults().keySet()) {
			prot.getBlastResults().put(
					blastDatabaseName,
					BlastResult.filterBestScoringBlastResults(prot
							.getBlastResults().get(blastDatabaseName), 200));
		}
	}

	/**
	 * Method initializes the AHRD-run: 1. Loads Proteins 2. Parses BlastResults
	 * 3. Parses InterproResults 4. Parses Gene-Ontology-Results
	 * 
	 * @throws IOException
	 * @throws MissingAccessionException
	 * @throws MissingProteinException
	 * @throws SAXException
	 * @throws ParsingException
	 */
	public void setup(boolean writeLogMsgs) throws IOException,
			MissingAccessionException, MissingProteinException, SAXException,
			ParsingException {
		if (writeLogMsgs)
			System.out.println("Started AHRD...\n");

		takeTime();

		initializeProteins();
		if (writeLogMsgs)
			System.out.println("...initialised proteins in " + takeTime()
					+ "sec, currently occupying " + takeMemoryUsage() + " MB");

		// multiple blast-results against different Blast-Databases
		parseBlastResults();
		if (writeLogMsgs)
			System.out.println("...parsed blast results in " + takeTime()
					+ "sec, currently occupying " + takeMemoryUsage() + " MB");

		// one single InterproResult-File
		if (getSettings().hasValidInterproDatabaseAndResultFile()) {
			InterproResult.initialiseInterproDb();
			parseInterproResult();
			if (writeLogMsgs)
				System.out.println("...parsed interpro results in "
						+ takeTime() + "sec, currently occupying "
						+ takeMemoryUsage() + " MB");
		}

		// one single Gene-Ontology-Annotation-File
		parseGeneOntologyResult();
		if (writeLogMsgs)
			System.out.println("...parsed gene ontology results in "
					+ takeTime() + "sec, currently occupying "
					+ takeMemoryUsage() + " MB");

	}

	/**
	 * Assign a HumanReadableDescription to each Protein
	 * 
	 * @throws MissingInterproResultException
	 * @throws IOException
	 */
	public void assignHumanReadableDescriptions()
			throws MissingInterproResultException, IOException {
		for (String protAcc : getProteins().keySet()) {
			Protein prot = getProteins().get(protAcc);
			// Find best scoring Blast-Hit's Description-Line (based on evalue):
			filterBestScoringBlastResults(prot);
			// Tokenize each BlastResult's Description-Line and
			// assign the Tokens their Scores:
			// tokenizeBlastResultDescriptionLines(prot);
			prot.getTokenScoreCalculator().assignTokenScores();
			// Tell informative from non-informative Tokens.
			// Assign each non-informative a new Score :=
			// currentScore - (Token-High-Score / 2)
			prot.getTokenScoreCalculator().filterTokenScores();
			// Find the highest scoring Blast-Result:
			prot.getDescriptionScoreCalculator()
					.findHighestScoringBlastResult();
			// filter for each protein's most-informative
			// interpro-results
			InterproResult.filterForMostInforming(prot);
		}
	}

	public Map<String, Protein> getProteins() {
		return proteins;
	}

	public void setProteins(Map<String, Protein> proteins) {
		this.proteins = proteins;
	}

	public Map<String, Double> getDescriptionScoreBitScoreWeights() {
		return descriptionScoreBitScoreWeights;
	}

	public void setDescriptionScoreBitScoreWeights(
			Map<String, Double> descriptionScoreBitScoreWeights) {
		this.descriptionScoreBitScoreWeights = descriptionScoreBitScoreWeights;
	}
}
