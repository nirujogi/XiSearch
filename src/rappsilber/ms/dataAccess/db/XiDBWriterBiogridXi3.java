/* 
 * Copyright 2016 Lutz Fischer <l.fischer@ed.ac.uk>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rappsilber.ms.dataAccess.db;

/**
 *
 * @author stahir
 */
//import com.jamonapi.Monitor;
import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.postgresql.PGConnection;
import rappsilber.config.RunConfig;
import rappsilber.db.ConnectionPool;
import rappsilber.ms.dataAccess.output.AbstractResultWriter;
import rappsilber.ms.sequence.Peptide;
import rappsilber.ms.sequence.Sequence;
import rappsilber.ms.sequence.fasta.FastaHeader;
import rappsilber.ms.spectra.Spectra;
import rappsilber.ms.spectra.SpectraPeak;
import rappsilber.ms.spectra.match.MatchedXlinkedPeptide;

/**
 *
 * @author stahir
 */
public class XiDBWriterBiogridXi3 extends AbstractResultWriter {

    private int m_search_id;
    private int m_acq_id;
    private RunConfig m_config;
    // these values are the same throughout the class so just set there values on init
    private int alpha_id;
    private int beta_id;
    protected ConnectionPool m_connectionPool;
    protected Connection m_conn;

    private PreparedStatement m_search_complete;
    private PreparedStatement m_match_type;
    protected PreparedStatement m_getIDs;
    protected PreparedStatement m_getIDsSingle;
    private PreparedStatement m_check_score;
    private PreparedStatement m_updateDB;
    private PreparedStatement m_insert_score;

    // ArrayList for materialized view EXPORT
    private int sqlBatchCount = 0;
    private int sqlBufferSize = 1000;
    private int results_processed = 0;
    private int top_results_processed = 0;
    private IDs ids;
    private HashMap<String,Long> runIds= new HashMap<>();
    
    private StringBuffer m_copySpectrumSource = new StringBuffer();
    
    private HashMap<String,Long> proteinIDs = new HashMap<>();
    

    // holds the start Ids for each result to save
    protected class IDs {
        class id {
            long last;
            long next;
            long inc;

            public id(long last, long next, long inc) {
                this.last = last;
                this.next = next;
                this.inc = inc;
            }
            public id(long inc) {
                this(-1, 0, inc);
            }
            
        }
        
        private id run =  new id(10);
        private id spec =  new id(2000);
        private id peak =  new id(100000);
        private id specMatch =  new id(10000);
        private id prot =  new id(10);
        private id pep =  new id(400);
        
        
        Connection dbconection;
        
        public IDs() {}
        
        public long reserveID(String name,long ids)  {
            try {
                Logger.getLogger(this.getClass().getName()).log(Level.FINE, "Reserving {0} IDs for {1}", new Object[]{ids, name});
                m_getIDsSingle.setString(1, name);
                m_getIDsSingle.setLong(2, ids);
                ResultSet rs = m_getIDsSingle.executeQuery();
                rs.next();
                return rs.getLong(1);
            } catch (SQLException ex) {
                Logger.getLogger(XiDBWriterBiogridXi3.class.getName()).log(Level.SEVERE, null, ex);
                throw new Error(ex);
            }
        }


        public long nextRunId() {
            if (run.next <= run.last) 
                return run.next++;
            
            run.next=reserveID("run_id",run.inc);
            run.last = run.next + run.inc -1;
            return run.next++;
        }        

        public long nextSpectrumId() {
            if (spec.next <= spec.last)
                return spec.next++;
            
            spec.next=reserveID("spectrum_id",spec.inc);
            spec.last = spec.next + spec.inc -1;
            return spec.next++;
        }

        public long nextPeakId() {
            if (peak.next <= peak.last)
                return peak.next++;
            
            peak.next=reserveID("peak_id",peak.inc);
            peak.last = peak.next + peak.inc -1;
            return peak.next++;
        }
        
        public long nextPeptideId() {
            if (pep.next <= pep.last)
                return pep.next++;
            
            pep.next=reserveID("peptide_id",pep.inc);
            pep.last = pep.next+ pep.inc -1;
            return pep.next++;
        }

        public long nextProteinId() {
            if (prot.next <= prot.last)
                return prot.next++;
            
            prot.next=reserveID("protein_id",prot.inc);
            prot.last += prot.next + prot.inc -1;
            return prot.next++;

        }

        public long nextSpectrumMatchId() {
            if (specMatch.next <= specMatch.last)
                return specMatch.next++;
            
            specMatch.next=reserveID("spectrum_match_id",specMatch.inc);
            specMatch.last = specMatch.next + specMatch.inc - 1;
            return specMatch.next++;
        }
        
    }
    
    public XiDBWriterBiogridXi3(RunConfig config, ConnectionPool cp, int searchID, int acq_id) {

        try {
            m_config = config;
            sqlBufferSize =  m_config.retrieveObject("SQLBUFFER", sqlBufferSize); // after reading how many spectra do we batch

            m_connectionPool = cp;
            m_search_id = searchID;
            
            m_acq_id = acq_id;
            m_conn = m_connectionPool.getConnection();
            ids = new IDs();

            m_search_complete = m_conn.prepareStatement("UPDATE search "
                    + "SET is_executing = 'false', status = 'completed', completed = 'true', percent_complete = 100 "
                    + "WHERE id = ?; ");


            m_match_type = m_conn.prepareStatement("SELECT id FROM match_type WHERE name = ?");


            // Used to get IDs add pass in 0
            m_getIDs = m_conn.prepareStatement("SELECT spectrumid, peakid, pepid, protid, specmatchid, paid, fragid, pcid "
                    + "FROM reserve_ids(?,?,?,?,?,?,?,?);");

            m_getIDsSingle = m_conn.prepareStatement("SELECT reserve_ids(?,?);");


            // Just get this value once and set them in the class
            alpha_id = -1;
            beta_id = -1;
            m_match_type.setString(1, "alpha");
            ResultSet rs = m_match_type.executeQuery();
            while (rs.next()) {
                alpha_id = rs.getInt(1);
            }

            m_match_type.setString(1, "beta");
            rs = m_match_type.executeQuery();
            while (rs.next()) {
                beta_id = rs.getInt(1);
            }
            rs.close();
            
            ResultSet sm = m_conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).executeQuery("SELECT id from spectrum_match where search_id = " + m_search_id + " limit 1");
            // do we have already results for this search?
            if (sm.next()) {
                String protein_query = "select id, accession_number, is_decoy , protein_length from protein where id in (select distinct protein_id  from (select *  from matched_peptide where search_id = "+m_search_id + ") mp inner join has_protein hp on mp.peptide_id = hp.peptide_id inner join protein p on hp.protein_id = p.id);";
                ResultSet proteins = m_conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).executeQuery(protein_query);
                while (proteins.next()) {
                    proteinIDs.put(proteins.getString(2) +proteins.getBoolean(3)  +proteins.getInt(4),  proteins.getLong(1));
                }
                proteins.close();
            }
            sm.close();
            

        } catch (SQLException ex) {
            System.err.println("XiDB: problem when setting up XiDBWriter: " + ex.getMessage());
            m_connectionPool.closeAllConnections();
            System.exit(1);
        }




    }
    
    
    public void setProteinIDIncrement(int count) {
        if (count>0)
            ids.prot.inc = count;
    }
    

    public void setPepetideIDIncrement(int count) {
        if (count>0)
            ids.pep.inc = count;
    }

    public void setSpectrumIDIncrement(int count) {
        if (count>0)
            ids.spec.inc = count;
    }

    public void setSpectrumMatchIDIncrement(int count) {
        if (count>0)
            ids.specMatch.inc = count;
    }

    public void setRunIDIncrement(int count) {
        if (count>0)
            ids.run.inc = count;
    }

    public void setPeakIDIncrement(int count) {
        if (count>0)
            ids.peak.inc = count;
    }
    

    
    private StringBuffer m_copySpectrum = new StringBuffer();

    
    public void addSpectrum(int acq_id, long run_id, Spectra s) {

        m_copySpectrum.append(acq_id);
        m_copySpectrum.append(",");
        m_copySpectrum.append(run_id);
        m_copySpectrum.append(",");
        m_copySpectrum.append(s.getScanNumber());
        m_copySpectrum.append(",");
        m_copySpectrum.append(s.getElutionTimeStart());
        m_copySpectrum.append(",");
        m_copySpectrum.append(s.getElutionTimeEnd());
        m_copySpectrum.append(",");
        m_copySpectrum.append(s.getID());
        m_copySpectrum.append(",");
        m_copySpectrum.append(s.getPrecoursorChargeAlternatives().length <= 1 ? s.getPrecurserCharge() : -1);
        m_copySpectrum.append(",");
        m_copySpectrum.append(s.getPrecurserIntensity());
        m_copySpectrum.append(",");
        m_copySpectrum.append(s.getPrecurserMZ());
        m_copySpectrum.append(",");
        m_copySpectrum.append(getSpectrumSourceID(s));
        m_copySpectrum.append("\n");

    }
    private StringBuffer m_spectrum_peakSql = new StringBuffer();

    public void addSpectrumPeak(Spectra s, SpectraPeak sp) {

        m_spectrum_peakSql.append(s.getID());
        m_spectrum_peakSql.append(",");
        m_spectrum_peakSql.append(sp.getMZ());
        m_spectrum_peakSql.append(",");
        m_spectrum_peakSql.append(sp.getIntensity());
        m_spectrum_peakSql.append(",");
        m_spectrum_peakSql.append(sp.getID());
        m_spectrum_peakSql.append("\n");


    }
    private StringBuffer m_peptideSql = new StringBuffer();

    public void addPeptide(Peptide p) {

        m_peptideSql.append("\"" + p.toString().replace("\"", "\"\"") + "\"");
        // m_peptideSql.append("',");
        m_peptideSql.append(",");
        m_peptideSql.append(p.getMass());
        m_peptideSql.append(",");
        m_peptideSql.append(p.getID());
        m_peptideSql.append(",");
        m_peptideSql.append(p.length());
//        m_peptideSql.append(",");
//        m_peptideSql.append(m_search_id);
        m_peptideSql.append("\n");
    }
    private StringBuffer m_proteinSql = new StringBuffer();

    public void addProtein(Sequence p) {
//                 postgres_con.getCopyAPI().copyIn(
//                        "COPY protein(header, name, accession_number, description, sequence, id, is_decoy, protein_length) " +
//                        "FROM STDIN WITH CSV", protis);

        String x = "";

        if (p.isDecoy()) {
            x = "DECOY";
        } else if (p.getFastaHeader() != null) {
            x = p.getFastaHeader().replace("\"", "\"\"").replace(",", " ");
        }//.replace("\\","\\\\").replace(",","\\,");
        FastaHeader fh = p.getSplitFastaHeader();
        //System.out.println("Fasta Header :" + x);

        String name  = fh.getName();
        if (name == null)
            name = fh.getAccession();
        if (name == null)
            name = "";
        
        String accession = fh.getAccession();
        if (accession == null)
            if (name.isEmpty())
                accession=""+p.getID();
            else
                accession = name;
        
        String description = fh.getDescription();
        if (description == null)
            description = name;
        
        m_proteinSql.append("\"");
        m_proteinSql.append(x);
        m_proteinSql.append("\",\"");
        m_proteinSql.append(name.replace("'", " ").replace(",", " "));
        m_proteinSql.append("\",\"");
        m_proteinSql.append(accession.replace("'", " ").replace(",", " "));
        m_proteinSql.append("\",\"");
        m_proteinSql.append(description.replace("\"", "\"\"").replace(",", " "));
//        m_proteinSql.append("',");
        m_proteinSql.append("\",\"");
        m_proteinSql.append(p.toString().replace("\"", "\"\""));
        m_proteinSql.append("\",");
        m_proteinSql.append(p.getID());
        m_proteinSql.append(",");
        m_proteinSql.append(p.isDecoy());
        m_proteinSql.append(",");
        m_proteinSql.append(p.length());
//        m_proteinSql.append(",");
//        m_proteinSql.append(m_search_id);
        m_proteinSql.append("\n");

    }
    private StringBuffer m_hasProteinSql = new StringBuffer();

    public void addHasProtein(Peptide p) {
        long pepid = p.getID();
        HashMap<Long, HashSet<Integer>> postions = new HashMap<Long, HashSet<Integer>>();

        boolean first = true;
        for (Peptide.PeptidePositions pp : p.getPositions()) {
            Long protID = pp.base.getID();
            Integer pepStart = pp.start;
            HashSet<Integer> protPos = postions.get(pp.base.getID());
            if (protPos == null) {
                protPos = new HashSet<Integer>();
                postions.put(pp.base.getID(), protPos);
            } else if (protPos.contains(pepStart)) {
                continue;
            }



            m_hasProteinSql.append(pepid);
            m_hasProteinSql.append(",");
            m_hasProteinSql.append(protID);
            m_hasProteinSql.append(",");
            m_hasProteinSql.append(pepStart);
            m_hasProteinSql.append(",");
            if (first) {
                m_hasProteinSql.append("true");
                first = false;
            } else {
                m_hasProteinSql.append("false");
            }
//            m_hasProteinSql.append(",");
//            m_hasProteinSql.append(m_search_id);
            m_hasProteinSql.append("\n");

        }

    }

   
    private StringBuffer m_SpectrumMatchSql = new StringBuffer();

    public void addSpectrumMatch(long searchID, double score, long spectrumID, long id, boolean is_decoy, MatchedXlinkedPeptide match) {
        // COPY spectrum_match(search_id, score, spectrum_id, id, is_decoy, rank, autovalidated, precursor_charge, calc_mass, dynamic_rank, scorepeptide1matchedconservative, scorepeptide2matchedconservative, scorefragmentsmatchedconservative, scorespectrumpeaksexplained, scorespectrumintensityexplained, scorelinksitedelta, scoredelta, scoremoddelta)
        m_SpectrumMatchSql.append(searchID);
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append(score);
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append(spectrumID);
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append(id);
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append(is_decoy);
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append(match.getMatchrank());
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append(match.isValidated());
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append(match.getSpectrum().getPrecurserCharge());
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append(match.getCalcMass());
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append(match.getMatchrank() == 1 ? true : false);
        // scorepeptide1matchedconservative, scorepeptide2matchedconservative, scorefragmentsmatchedconservative, scorespectrumpeaksexplained, scorespectrumintensityexplained, scorelinksitedelta
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append((int)match.getScore("peptide1 unique matched conservative"));
        //scorepeptide2matchedconservative
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append((int)match.getScore("peptide2 unique matched conservative"));
        //scorefragmentsmatchedconservative
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append((int)match.getScore("fragment unique matched conservative"));
        // scorespectrumpeaksexplained
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append(match.getScore("spectrum peaks coverage"));
        //scorespectrumintensityexplained, 
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append(match.getScore("spectrum intensity coverage"));
        //scorelinksitedelta
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append(match.getScore("LinkSiteDelta"));
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append(match.getScore("delta"));
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append(match.getScore("deltaMod"));
        
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append(match.getScore("mgcAlpha"));
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append(match.getScore("mgcBeta"));
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append(match.getScore("mgcScore"));
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append((int)match.getScore("mgxRank"));
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append(match.getScore("mgxScore"));
        m_SpectrumMatchSql.append(",");
        m_SpectrumMatchSql.append(match.getScore("mgxDelta"));
        m_SpectrumMatchSql.append("\n");

        
    }
    private StringBuffer m_MatchedPeptideSql = new StringBuffer();

    public void addMatchedPeptide(Peptide p, long matchid, long matchtype, long link_position, boolean display_positon, Integer crosslinker_id, Integer crosslinker_number) {
        m_MatchedPeptideSql.append(p.getID());
        m_MatchedPeptideSql.append(",");
        m_MatchedPeptideSql.append(matchid);
        m_MatchedPeptideSql.append(",");
        m_MatchedPeptideSql.append(matchtype);
        m_MatchedPeptideSql.append(",");
        m_MatchedPeptideSql.append(link_position);
        m_MatchedPeptideSql.append(",");
        m_MatchedPeptideSql.append(display_positon);
        m_MatchedPeptideSql.append(",");
        m_MatchedPeptideSql.append(crosslinker_id == null ? "" : crosslinker_id);
        m_MatchedPeptideSql.append(",");
        m_MatchedPeptideSql.append(crosslinker_number == null ? "" : crosslinker_number);
        m_MatchedPeptideSql.append(",");
        m_MatchedPeptideSql.append(m_search_id);
        m_MatchedPeptideSql.append("\n");
    }


    private synchronized void executeCopy() {

        try {
            PGConnection postgres_con = null;
            Connection con = null;
            try {
                // Cast to a postgres connection
                con = m_connectionPool.getConnection();
                postgres_con = (PGConnection) con;
            } catch (SQLException ex) {
                Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, ex);
                throw new Error(ex);
            }

//            Connection conExportMat = null;
//            try {
//                // Cast to a postgres connection
//                conExportMat = m_connectionPool.getConnection();
//            } catch (SQLException ex) {
//                Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, ex);
//                return;
//            }
//            final PGConnection postgresConExportMat = (PGConnection) conExportMat;

//            Connection conSpecViewerMat = null;
//            try {
//                // Cast to a postgres connection
//                conSpecViewerMat = m_connectionPool.getConnection();
//            } catch (SQLException ex) {
//                Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, ex);
//                return;
//            }
//            final PGConnection postgresConSpecViewerMat = (PGConnection) conSpecViewerMat;

            final CyclicBarrier waitSync = new CyclicBarrier(3);




            try {
                con.setAutoCommit(false);
            } catch (SQLException ex) {
                Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, ex);
                throw new Error(ex);
            }
                
            {
                if (m_copySpectrumSource.length() >0) {
                    String spectrumSourceCopy = m_copySpectrumSource.toString();
                    byte sByte[] = spectrumSourceCopy.getBytes();
                    InputStream is = new ByteArrayInputStream(sByte);
                    m_copySpectrumSource.setLength(0);
        //            System.out.println("spectrum " + postgres_con.getCopyAPI().copyIn(
        //                    "COPY spectrum (acq_id, run_id, scan_number, elution_time_start, elution_time_end, id) " +
        //                    "FROM STDIN WITH CSV", is));


                    try {
                        postgres_con.getCopyAPI().copyIn(
                                "COPY spectrum_source (id, name) "
                                + "FROM STDIN WITH CSV", is);
                    } catch (SQLException ex) {
                        String message = "error writing the spectra informations";
                        Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, message, ex);
                        PrintWriter pw = new PrintWriter(new FileOutputStream("/tmp/XiCopySqlError.csv", true));
                        pw.println("\n------------------------------------------------\n" + new Date() + " " + message);
                        ex.printStackTrace(pw);
                        pw.println("->");
                        pw.println(spectrumSourceCopy);
                        pw.close();
                        return;
                    }
                } else if (runIds.size() == 0) {
                    Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, "trying to store something but have no spectrum_source data");
                }
                //
                String spectrumCopy = m_copySpectrum.toString();
                byte[] sByte= spectrumCopy.getBytes();
                InputStream is = new ByteArrayInputStream(sByte);
                m_copySpectrum.setLength(0);
    //            System.out.println("spectrum " + postgres_con.getCopyAPI().copyIn(
    //                    "COPY spectrum (acq_id, run_id, scan_number, elution_time_start, elution_time_end, id) " +
    //                    "FROM STDIN WITH CSV", is));


                try {
                    postgres_con.getCopyAPI().copyIn(
                            "COPY spectrum (acq_id, run_id, scan_number, elution_time_start, elution_time_end, id, precursor_charge, precursor_intensity, precursor_mz, source_id) "
                            + "FROM STDIN WITH CSV", is);
                            //              1982,         1,       3182,               -1.0,       -1.0,148797622,               -1,                -1.0, 335.4323873,10001,2000080
                } catch (SQLException ex) {
                    String message = "error writing the spectra informations";
                    Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, message, ex);
                    PrintWriter pw = new PrintWriter(new FileOutputStream("/tmp/XiCopySqlError.csv", true));
                    pw.println("\n------------------------------------------------\n" + new Date() + " " + message);
                    ex.printStackTrace(pw);
                    pw.println("->");
                    pw.println(spectrumCopy);
                    pw.close();
                    try {
                        con.rollback();
                    } catch (SQLException ex1) {
                        Logger.getLogger(XiDBWriterBiogridXi3.class.getName()).log(Level.SEVERE, null, ex1);
                    }
                    throw new Error(ex);
                }
            }
            
            {
                // Spectrum Peak
                final String spectrumPeakCopy = m_spectrum_peakSql.toString();
                byte spByte[] = spectrumPeakCopy.getBytes();
                InputStream isp = new ByteArrayInputStream(spByte);
                m_spectrum_peakSql.setLength(0);
    //            System.out.println("spectrum_peak " + postgres_con.getCopyAPI().copyIn(
    //                    "COPY spectrum_peak (spectrum_id, mz, intensity, id)" +
    //                    "FROM STDIN WITH CSV", isp));
                try {
                    postgres_con.getCopyAPI().copyIn(
                            "COPY spectrum_peak (spectrum_id, mz, intensity, id)"
                            + "FROM STDIN WITH CSV", isp);
                } catch (SQLException ex) {
                    String message = "error writing the spectra peak informations";
                    Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, message, ex);
                    PrintWriter pw = new PrintWriter(new FileOutputStream("/tmp/XiCopySqlError.csv", true));
                    pw.println("\n------------------------------------------------\n" + new Date() + " " + message);
                    ex.printStackTrace(pw);
                    pw.println("->");
                    pw.println(spectrumPeakCopy);
                    try {
                        con.rollback();
                    } catch (SQLException ex1) {
                        Logger.getLogger(XiDBWriterBiogridXi3.class.getName()).log(Level.SEVERE, null, ex1);
                    }
                    throw new Error(ex);
                }
            }
            // Peptide
            {
                String peptideCopy = m_peptideSql.toString();
                byte peptideByte[] = peptideCopy.getBytes();
                InputStream pis = new ByteArrayInputStream(peptideByte);
                m_peptideSql.setLength(0);
    //             System.out.println("peptide " + postgres_con.getCopyAPI().copyIn(
    //                    "COPY peptide(sequence, mass, id) " +
    //                    "FROM STDIN WITH CSV", pis));
                try {
                    postgres_con.getCopyAPI().copyIn(
                            "COPY peptide(sequence, mass, id, peptide_length) "
                            + "FROM STDIN WITH CSV", pis);
                } catch (SQLException ex) {
                    String message = "error writing the peptide informations";
                    Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, message, ex);
                    PrintWriter pw = new PrintWriter(new FileOutputStream("/tmp/XiCopySqlError.csv", true));
                    pw.println("\n------------------------------------------------\n" + new Date() + " " + message);
                    ex.printStackTrace(pw);
                    pw.println("->");
                    pw.println(peptideCopy);
                    pw.flush();
                    try {
                        con.rollback();
                    } catch (SQLException ex1) {
                        Logger.getLogger(XiDBWriterBiogridXi3.class.getName()).log(Level.SEVERE, null, ex1);
                    }
                    throw new Error(ex);
                }
                peptideCopy = null;
            }

            // Protein
            {
                String proteinCopy = m_proteinSql.toString();
                //System.err.println("to save>> " + m_proteinSql.toString());
                byte protByte[] = proteinCopy.getBytes();
                InputStream protis = new ByteArrayInputStream(protByte);
                m_proteinSql.setLength(0);
    //             System.out.println("protein " + postgres_con.getCopyAPI().copyIn(
    //                    "COPY protein(name, sequence, id) " +
    //                    "FROM STDIN WITH CSV", protis));
                // System.err.println(protis);
                try {
                    postgres_con.getCopyAPI().copyIn(
                            "COPY protein(header,name, accession_number, description, sequence, id, is_decoy, protein_length) "
                            + "FROM STDIN WITH CSV", protis);
                } catch (SQLException ex) {
                    String message = "error writing the protein informations";
                    Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, message, ex);
                    PrintWriter pw = new PrintWriter(new FileOutputStream("/tmp/XiCopySqlError.csv", true));
                    pw.println("\n------------------------------------------------\n" + new Date() + " " + message);
                    ex.printStackTrace(pw);
                    pw.println("->");
                    pw.println(proteinCopy);
                    try {
                        con.rollback();
                    } catch (SQLException ex1) {
                        Logger.getLogger(XiDBWriterBiogridXi3.class.getName()).log(Level.SEVERE, null, ex1);
                    }
                    throw new Error(ex);
                }
                proteinCopy=null;
            }

            // has_protein
            {
                String hpCopy = m_hasProteinSql.toString();
                byte hpByte[] = hpCopy.getBytes();
                // System.err.println(hpCopy);
                InputStream hpis = new ByteArrayInputStream(hpByte);
                m_hasProteinSql.setLength(0);
    //             System.out.println("has_protein " + postgres_con.getCopyAPI().copyIn(
    //                    "COPY has_protein(peptide_id, protein_id, peptide_position, display_site) " +
    //                    "FROM STDIN WITH CSV", hpis));
                try {
                    postgres_con.getCopyAPI().copyIn(
                            "COPY has_protein(peptide_id, protein_id, peptide_position, display_site) "
                            + "FROM STDIN WITH CSV", hpis);
                } catch (SQLException ex) {
                    String message = "error writing the hasprotein table";
                    Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, message, ex);
                    PrintWriter pw = new PrintWriter(new FileOutputStream("/tmp/XiCopySqlErrorHasProtein.csv", true));
                    pw.println("\n------------------------------------------------\n" + new Date() + " " + message);
                    ex.printStackTrace(pw);
                    pw.println("->");
                    pw.println(hpCopy);
                    pw.flush();
                    try {
                        con.rollback();
                    } catch (SQLException ex1) {
                        Logger.getLogger(XiDBWriterBiogridXi3.class.getName()).log(Level.SEVERE, null, ex1);
                    }
                    throw new Error(ex);
                }
                hpCopy = null;
            }




            
            // spetcrum_match
            {
                final String specCopy = m_SpectrumMatchSql.toString();
                byte specByte[] = specCopy.getBytes();
                InputStream specis = new ByteArrayInputStream(specByte);
                m_SpectrumMatchSql.setLength(0);
    //             System.out.println("spectrum_match " + postgres_con.getCopyAPI().copyIn(
    //                    "COPY spectrum_match(search_id, score, spectrum_id, id) " +
    //                    "FROM STDIN WITH CSV", specis));
                try {
                    postgres_con.getCopyAPI().copyIn(
                            "COPY spectrum_match(search_id, score, spectrum_id, id, is_decoy, rank, autovalidated, precursor_charge, calc_mass, dynamic_rank, scorepeptide1matchedconservative, scorepeptide2matchedconservative, scorefragmentsmatchedconservative, scorespectrumpeaksexplained, scorespectrumintensityexplained, scorelinksitedelta, scoredelta, scoremoddelta,scoreMGCAlpha,ScoreMGCBeta,ScoreMGC,ScoreMGXRank, ScoreMGX, ScoreMGXDelta) "
                            + "FROM STDIN WITH CSV", specis);
                } catch (SQLException ex) {
                    String message = "error writing the spectrum_match table";
                    Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, message, ex);
                    PrintWriter pw = new PrintWriter(new FileOutputStream("/tmp/XiCopySqlError.csv", true));
                    pw.println("\n------------------------------------------------\n" + new Date() + " " + message);
                    ex.printStackTrace(pw);
                    pw.println("->");
                    pw.println(specCopy);
                    try {
                        con.rollback();
                    } catch (SQLException ex1) {
                        Logger.getLogger(XiDBWriterBiogridXi3.class.getName()).log(Level.SEVERE, null, ex1);
                    }
                    throw new Error(ex);
                }
            }
            

            {
                // matched_peptide
                final String mpCopy = m_MatchedPeptideSql.toString();
                byte mpByte[] = mpCopy.getBytes();
                InputStream mpis = new ByteArrayInputStream(mpByte);
                m_MatchedPeptideSql.setLength(0);
    //             System.out.println("matched_peptide " + postgres_con.getCopyAPI().copyIn(
    //                    "COPY matched_peptide(peptide_id, match_id, match_type, link_position, display_positon) " +
    //                    "FROM STDIN WITH CSV", mpis));
                try {
                    postgres_con.getCopyAPI().copyIn(
                            "COPY matched_peptide(peptide_id, match_id, match_type, link_position, display_positon, crosslinker_id, crosslinker_number, search_id) "
                            + "FROM STDIN WITH CSV", mpis);
                } catch (SQLException ex) {
                    String message = "error writing the matched_peptide table";
                    Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, message, ex);
                    PrintWriter pw = new PrintWriter(new FileOutputStream("/tmp/XiCopySqlError.csv", true));
                    pw.println("\n------------------------------------------------\n" + new Date() + " " + message);
                    ex.printStackTrace(pw);
                    pw.println("->");
                    pw.println(mpCopy);
                    try {
                        con.rollback();
                    } catch (SQLException ex1) {
                        Logger.getLogger(XiDBWriterBiogridXi3.class.getName()).log(Level.SEVERE, null, ex1);
                    }
                    throw new Error(ex);
                }
            }
            
            
            // join up with the threads, that write the materialized views
//            try {
//                waitSync.await();
//            } catch (InterruptedException ex) {
//                Logger.getLogger(XiDBWriterBiogridXi3.class.getName()).log(Level.SEVERE, null, ex);
//            } catch (BrokenBarrierException ex) {
//                Logger.getLogger(XiDBWriterBiogridXi3.class.getName()).log(Level.SEVERE, null, ex);
//            }

            try {
                con.commit();
            } catch (SQLException ex1) {
                Logger.getLogger(XiDBWriterBiogridXi3.class.getName()).log(Level.SEVERE, null, ex1);
            }
            
            // free the connection
            m_connectionPool.free(con);
//            m_connectionPool.free(conExportMat);
//            m_connectionPool.free(conSpecViewerMat);




        } catch (IOException ex) {
            Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, ex);
        }




    }// end method


    protected void init(int maxBatchSize, String score) {
    }

    @Override
    public void writeHeader() {
        // updating the search table to 'executing' if not already done - check
    }


    @Override
    public synchronized void writeResult(MatchedXlinkedPeptide match) {

        ++results_processed;
        if (match.getMatchrank() == 1)
            top_results_processed++;

        sqlBatchCount++;
        if (sqlBatchCount > sqlBufferSize) { //sqlBufferSize/10){
            executeCopy();
            sqlBatchCount = 0;
        }


        // 1. Check spectrum info spectrum spectrum peak
        Spectra matched_spectrum = match.getSpectrum();

        if (matched_spectrum.getID() == -1) {
            saveSpectrum(matched_spectrum, ids);
        }




        // 2. Save spectrum_match info
        double score = match.getScore("match score");
        if (Double.isNaN(score) || Double.isNaN(score)) {
            score = 0;
        }
        long spectrum_match_id = saveSpectrumMatch(match.getScore("match score"), matched_spectrum.getID(), ids, match.isDecoy(), match);

        // 3. Save the protein/peptide sequences
//        mon3 = MonitorFactory.start("savePeptide1()");
        boolean alpha = true;
        Peptide[] peps = match.getPeptides();
        for (int p = 0; p<peps.length; p++) {
            savePeptide(peps[p], spectrum_match_id, alpha, match.getLinkSites(peps[p]), ids, match.getCrosslinker() == null? null : match.getCrosslinker().getDBid(),0);
            alpha = false;
        }




    }// write result

    private long getSpectrumSourceID(Spectra matched_spectrum){
        String run = matched_spectrum.getRun();
        Long id = runIds.get(run);
        if (id == null) {
            id = ids.nextRunId();
            runIds.put(run, id);
            m_copySpectrumSource.append(id).append(",\"").append(run.replaceAll("\"", "\\\"")).append("\"\n");
        }
        return id;
    }
    
    private void saveSpectrum(Spectra matched_spectrum, IDs ids) {

        
        
        matched_spectrum.setID(ids.nextSpectrumId());
        addSpectrum(m_acq_id, matched_spectrum.getRunID(), matched_spectrum);


        for (SpectraPeak sp : matched_spectrum.getPeaksArray()) {
            sp.setID(ids.nextPeakId());
            addSpectrumPeak(matched_spectrum, sp);

        }
        



    }//



    private void savePeptide(Peptide peptide, long match_id, boolean alpha, int[] linkSites, IDs result_ids, Integer crosslinker_id, Integer crosslinker_number) {

        // if this is the first time you see a peptide, then save it to the DB, and set the ID
        // Likewise do the same with the Protein
//        try {
        if (peptide.getID() == -1) {

            //             id       | bigint  | not null default nextval('peptide_id_seq'::regclass)
            //             sequence | text    |
            //             mass     | numeric |
            peptide.setID(result_ids.nextPeptideId());
            addPeptide(peptide);


            // Now check if there is a problem with the Proteins i.e. have we seen them before

            int first = 0;
            for (Peptide.PeptidePositions pp : peptide.getPositions()) {
                ++first;
                Sequence protein = pp.base;
                if (protein.getID() == -1) {

                    // check if we recovered the id from the database
                    Long id = proteinIDs.get(protein.getSplitFastaHeader().getAccession()+protein.isDecoy()+protein.length());
                    if (id == null) {
                        protein.setID(result_ids.nextProteinId());
                        addProtein(protein);

                    } else {
                        protein.setID(id);
                    }


                }


            }
            addHasProtein(peptide);





        }// end if peptide

        // Save matched_peptide information

        // For each link position , save the match in matched peptides
        // initially this we are forwardede only 1, in future more will come
        for (int i = 0; i < linkSites.length; i++) {
            addMatchedPeptide(peptide, match_id, (alpha ? alpha_id : beta_id), linkSites[i], (i == 0), crosslinker_id, crosslinker_number);
        }// end for


    }// end method

    @Override
    public int getResultCount() {
        return results_processed;
    }

    @Override
    public int getTopResultCount() {
        return top_results_processed;
    }
    
    @Override
    public void setFreeMatch(boolean doFree) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public synchronized void flush() {
        if (sqlBatchCount > 0) {
            executeCopy();
        }

    }

    @Override
    public void finished() {

        try {
            flush();
//                executeSQL();
//                  writeInserts();


            // our search is done
            m_search_complete.setInt(1, m_search_id);
            m_search_complete.executeUpdate();

            // runtime stats
            Logger.getLogger(this.getClass().getName()).log(Level.INFO,"XiDBWriterCopySql - Total results: " + getResultCount() + "\n-------------");

        } catch (SQLException ex) {
            Logger.getLogger(this.getClass().getName()).log(Level.SEVERE,"XiDB: problem when writing last results " + ex.getMessage());
            m_connectionPool.closeAllConnections();
            System.exit(1);
        }

        super.finished();
    }

    private long saveSpectrumMatch(double match_score, long spec_id, IDs result_ids, boolean is_decoy, MatchedXlinkedPeptide match) {
        long spec_match_id = result_ids.nextSpectrumMatchId();
//        try{
        addSpectrumMatch(m_search_id, match_score, spec_id, spec_match_id, is_decoy, match);

        return spec_match_id;
    }

}
