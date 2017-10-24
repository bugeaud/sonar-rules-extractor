package fr.bugeaud.sonar.rules.extractor;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.ObjectMapper;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import com.mashape.unirest.request.HttpRequest;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.poi.hssf.util.AreaReference;
import org.apache.poi.hssf.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFCreationHelper;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFTable;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTTable;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTTableColumn;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTTableColumns;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTTableStyleInfo;
import fr.bugeaud.tools.sonar.rules.client.SonarRule;
import fr.bugeaud.tools.sonar.rules.client.RulesExtract;

/**
 * Tool that extract rules from a SonarQube instance
 * @author bugeaud at gmail dot com
 * @licence CeCILL 2.1
 */
public class RulesExtractor {
    
    @Parameter(names = "-v", description = "Verbose, print more log (info, errors ...)")
    private boolean verbose = false;
    
    @Parameter(names = "-help", help = true, description = "Shows this help")
    private boolean help = false;

    public boolean isHelp(){
        return help;
    }
  
    public boolean isVerbose(){
        return verbose;
    }
    
    public static String DEFAULT_LANGUAGE = "java";
    
    @Parameter(names = "-l", description = "Give indication for the target language code to extract. It must be valid languages codes from SonarQube. Multiple languages codes are accepted when separated by a comma. By default, it will use java language.")
    private String language = DEFAULT_LANGUAGE;
    
    @Parameter(names = "-s", description = "Search URI, by default it will use the public SonarQube Search API thru HTTPS.")
    private String searchUri= PUBLIC_SONAR_SEARCH_URI;
    /*
     Your might perfquery SonarQube API :
     https://sonarqube.com/api/rules/search?languages=cpp&available_since=2016-09-01
    */
    
    @Parameter(names = "-d", description = "Limit search to the given date.", converter = LocalDateConverter.class)
    private LocalDate localDate;
    
    @Parameter(names = "-o", required = true, description = "Indicate the outputPath", converter = PathConverter.class)
    private Path outputPath;
    
    @Parameter(names = "-e", description = "List column headers to display in the result" )
    private List<String> headers = new ArrayList<>();
    
    public void tryInitDefaultHeaders(){
        if(getHeaders() != null && getHeaders().isEmpty()){
            // Then init with default headers
            setHeaders(Arrays.asList("id","key","status","name","createdAt","langName","htmlDesc","severity","status","sysTags","type","source","category","remFnType","remFnBaseEffort","repo","comment"));
        }
    }
    
    protected static final Logger LOGGER = Logger.getLogger(RulesExtractor.class.getName());
    
    /**
     * List all the rules for the given languages from the given repository starting from a given date
     * @param uri the SonarQube rule repository
     * @param language the target language identifier. If there are multiples, the various identifiers will be separated by a comma.
     * @param from the cut-off date, only rules created earlier on will be selected in the result 
     * @return the list of rules in the repository matching the languages and not older than the from date
     * @throws UnirestException if the Unirest has failed
     */
    public static List<SonarRule> listRules(String uri, String language, LocalDate from) throws UnirestException{
        // Keep the whole rules catalog, as responses to query will be paginated
        final RulesExtract wholeCatalog = new RulesExtract();
        
        int page = 0;
        final int pageSize = 500;
        int totalPage = 0;
        do{
            page++;
            // Build a query with the laguage
            HttpRequest request = Unirest.get(uri).queryString("languages",language).queryString("ps", pageSize).queryString("p", page);
            
            if(from!=null){
                request = request.queryString("available_since", from.format(DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.of("UTC"))));
            }
            final HttpResponse<RulesExtract> bookResponse = request.asObject(RulesExtract.class);
            
            RulesExtract extract = bookResponse.getBody();
            wholeCatalog.setP(extract.getP());
            wholeCatalog.setTotal(extract.getTotal());
            wholeCatalog.getRules().addAll(extract.getRules());

            // Let's refresh the total page count
            totalPage = extract.getTotal()/pageSize;
        }while(page<=totalPage);
        
        return wholeCatalog.getRules();
    }
    
    public static final int NO_RULES_EXIT_CODE = 3;
    public static final int USAGE_EXIT_CODE = 2;
    public static final String ROOT_LOGGER = "" ;
    
    public static void main(String[] args) throws Exception{
        final RulesExtractor extractor = new RulesExtractor();
        final JCommander commander = new JCommander(extractor,args);

        // Display the help when required and exit
        if(extractor.isHelp()){
            commander.usage();
            System.exit(USAGE_EXIT_CODE);
        }
        
        // Change the level of the root handlers to INFO if verbose was set
        if(extractor.isVerbose()){
            final Handler[] handlers = Logger.getLogger( ROOT_LOGGER ).getHandlers();
            for (Handler handler : handlers) {
                handler.setLevel(Level.INFO);
                Logger.getLogger(RulesExtractor.class.getName()).log(Level.INFO, String.format("Handler %s was set to verbose (INFO)", handler));
            }            
        }
        
        // Perform the document generation task
        //extractor.generateDocument();
        
        //LocalDate.parse("test", DateTimeFormatter.ofPattern("yyyy-MMM-d").withLocale(Locale.US));
        /*
        sonarQubeReleases().forEach((k,v)->{
            System.out.printf("%s %s\n",k,v);
        });*/
        
        // Let's try to init the headers with default values if there was none set as parameters
        extractor.tryInitDefaultHeaders();        
        
        final List<SonarRule> rules = extractor.extractRules();
        
        
        
        if(rules==null){
            LOGGER.warning("Empty rules list, can not generate anything");
            System.exit(NO_RULES_EXIT_CODE);
            return;
        }
        LOGGER.info(String.format("There was %s rules found", rules.size()));
        
        /*
        rules.stream().forEach((r)->{
            System.out.printf("%s\n", r.getKey());
        });
        */
        
        extractor.generateExcel(rules);
                
        //FileOutputStream outputStream = new FileOutputStream("C:\\tmp\\tmp-dass\\test-abap.xlsx");
        //extractor.generateExcelTable(rules, Arrays.asList("id","key","status","name","createdAt","langName","htmlDesc","severity","status","sysTags","type","source","category","automated","remFnType","remFnBaseEffort","repo","comment") , outputStream);
    }
    
    static final DateTimeFormatter RELEASE_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MMM-d").withLocale(Locale.US);
    
    static void createSonarRelease(Map<String,LocalDate> releases, String version, String releaseDate){
        releases.put(version, LocalDate.parse(releaseDate, RELEASE_DATE_FORMATTER));
    }
    
    static Map<String,LocalDate> sonarQubeReleases(){
        Map<String, LocalDate> releases = new HashMap<>();
createSonarRelease(releases,"6.3.1","2017-Apr-12");
createSonarRelease(releases,"6.3","2017-Mar-14");
createSonarRelease(releases,"5.6.6","2017-Feb-17");
createSonarRelease(releases,"5.6.5","2017-Jan-19");
createSonarRelease(releases,"5.6.4","2016-Dec-12");
createSonarRelease(releases,"5.6.3","2016-Oct-4");
createSonarRelease(releases,"5.6.2","2016-Sep-19");
createSonarRelease(releases,"5.6.1","2016-Jul-27");
createSonarRelease(releases,"5.6","2016-Jun-3");
createSonarRelease(releases,"6.2","2016-Dec-14");
createSonarRelease(releases,"6.1","2016-Oct-13");
createSonarRelease(releases,"6.0","2016-Aug-4");
createSonarRelease(releases,"5.5","2016-May-3");
createSonarRelease(releases,"5.4","2016-Mar-9");
createSonarRelease(releases,"5.3","2016-Jan-11");
createSonarRelease(releases,"5.2","2015-Nov-2");
createSonarRelease(releases,"5.1.2","2015-Jul-27");
createSonarRelease(releases,"5.1.1","2015-Jun-5");
createSonarRelease(releases,"5.0.1","2015-Feb-24");
createSonarRelease(releases,"5.0","2015-Jan-14");
createSonarRelease(releases,"4.5.7","2016-Apr-8");
createSonarRelease(releases,"4.5.6","2015-Oct-16");
createSonarRelease(releases,"4.5.5","2015-Jul-30");
createSonarRelease(releases,"4.5.4","2015-Feb-26");
createSonarRelease(releases,"4.5.2","2015-Jan-7");
createSonarRelease(releases,"4.5.1","2014-Oct-29");
createSonarRelease(releases,"4.5","2014-Sep-29");
createSonarRelease(releases,"4.4.1","2014-Sep-26");
createSonarRelease(releases,"4.4","2014-Jul-31");
createSonarRelease(releases,"4.3.3","2014-Jul-31");
createSonarRelease(releases,"4.3.2","2014-Jun-24");
createSonarRelease(releases,"4.3.1","2014-Jun-4");
createSonarRelease(releases,"4.3","2014-May-2");
createSonarRelease(releases,"4.2","2014-Mar-26");
createSonarRelease(releases,"4.1.2","2014-Feb-20");
createSonarRelease(releases,"4.1.1","2014-Jan-28");
createSonarRelease(releases,"4.1","2014-Jan-13");
createSonarRelease(releases,"4.0","2013-Nov-7");
createSonarRelease(releases,"3.7.4","2013-Dec-20");
createSonarRelease(releases,"3.7.3","2013-Oct-21");
createSonarRelease(releases,"3.7.2","2013-Oct-2");
createSonarRelease(releases,"3.7.1","2013-Sep-23");
createSonarRelease(releases,"3.7","2013-Aug-14");
createSonarRelease(releases,"3.6.3","2013-Aug-14");
createSonarRelease(releases,"3.6.2","2013-Jul-18");
createSonarRelease(releases,"3.6.1","2013-Jul-12");
createSonarRelease(releases,"3.6","2013-Jun-26");
createSonarRelease(releases,"3.5.1","2013-Apr-3");
createSonarRelease(releases,"3.5","2013-Mar-13");
createSonarRelease(releases,"3.4.1","2013-Jan-8");
createSonarRelease(releases,"3.4","2012-Dec-22");
createSonarRelease(releases,"3.3.2","2012-Nov-21");
createSonarRelease(releases,"3.3.1","2012-Nov-07");
createSonarRelease(releases,"3.3","2012-Oct-24");
createSonarRelease(releases,"3.2.1","2012-Oct-3");
createSonarRelease(releases,"3.2","2012-Aug-6");
createSonarRelease(releases,"3.1.1","2012-Jun-25");
createSonarRelease(releases,"3.1","2012-Jun-13");
createSonarRelease(releases,"3.0.1","2012-May-14");
createSonarRelease(releases,"3.0","2012-Apr-17");
createSonarRelease(releases,"2.14","2012-Mar-19");
createSonarRelease(releases,"2.13.1","2012-Jan-31");
createSonarRelease(releases,"2.12","2011-Nov-30");
createSonarRelease(releases,"2.11","2011-Oct-3");
createSonarRelease(releases,"2.10","2011-Aug-18");
createSonarRelease(releases,"2.9","2011-Jul-18");
createSonarRelease(releases,"2.8","2011-May-19");
createSonarRelease(releases,"2.7","2011-Apr-1");
createSonarRelease(releases,"2.6","2011-Feb-18");
createSonarRelease(releases,"2.5","2011-Jan-14");
createSonarRelease(releases,"2.4.1","2010-Nov-18");
createSonarRelease(releases,"2.3.1","2010-Oct-22");
createSonarRelease(releases,"2.2","2010-Jul-15");
createSonarRelease(releases,"2.1.2","2010-May-20");
createSonarRelease(releases,"2.0.1","2010-Mar-10");
createSonarRelease(releases,"1.12","2009-Dec-7");
createSonarRelease(releases,"1.11.1","2009-Oct-20");
createSonarRelease(releases,"1.11","2009-Oct-5");
createSonarRelease(releases,"1.10.1","2009-Aug-19");
createSonarRelease(releases,"1.10","2009-Aug-14");
createSonarRelease(releases,"1.9.2","2009-Jun-8");
createSonarRelease(releases,"1.9","2009-May-25");
createSonarRelease(releases,"1.8","2009-Apr-17");
createSonarRelease(releases,"1.7","2009-Mar-18");
createSonarRelease(releases,"1.6","2009-Feb-9");
createSonarRelease(releases,"1.5.1","2009-Jan-8");
createSonarRelease(releases,"1.5","2008-Dec-16");
createSonarRelease(releases,"1.4.3","2008-Oct-16");
createSonarRelease(releases,"1.4.2","2008-Sep-25");
createSonarRelease(releases,"1.4.1","2008-Aug-23");
createSonarRelease(releases,"1.4","2008-Aug-7");
createSonarRelease(releases,"1.3","2008-Jun-16");
createSonarRelease(releases,"1.2.1","2008-Apr-30");
createSonarRelease(releases,"1.2","2008-Mar-26");
createSonarRelease(releases,"1.1","2008-Feb-25");
createSonarRelease(releases,"1.0.2","2007-Dec-14");

        return releases;
    }
    
    public static final String PUBLIC_SONAR_SEARCH_URI = "https://sonarqube.com/api/rules/search";
    
    public List<SonarRule> extractRules() throws Exception{
        return extractRules(getSearchUri(),getLanguage(),getLocalDate());
    }
    
    public List<SonarRule> extractRules(String uri, String lang, LocalDate from) throws Exception{
                // Only one time
        Unirest.setObjectMapper(new ObjectMapper() {
            private com.fasterxml.jackson.databind.ObjectMapper jacksonObjectMapper
                        = new com.fasterxml.jackson.databind.ObjectMapper();

            @Override
            public <T> T readValue(String value, Class<T> valueType) {
                try {
                    return jacksonObjectMapper.readValue(value, valueType);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public String writeValue(Object value) {
                try {
                    return jacksonObjectMapper.writeValueAsString(value);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        
        return listRules(uri, lang, from);
        
        //final Instant cutInstant = Instant.parse("2016-09-01T00:00:00Z");
        
        //final List<SonarRule> wholeCatalog = listRules("cpp", cutInstant);
        //final List<SonarRule> wholeCatalog = listRules(uri, lang, from);
        /*
        // Response to Object
        HttpResponse<RulesExtract> bookResponse = Unirest.get("https://sonarqube.com/api/rules/search?languages=cpp&available_since=2016-09-01").asObject(RulesExtract.class);
        RulesExtract extract = bookResponse.getBody();
        
        HttpResponse<RulesExtract> bookResponse2 = Unirest.get("https://sonarqube.com/api/rules/search?languages=cpp&available_since=2016-09-01").asObject(RulesExtract.class);
        */
        /*System.out.println("Size"+wholeCatalog.size());
        
        wholeCatalog.stream().forEach((r)->{
            System.out.printf("%s\n", r.getKey());
        });
        
        */
    }
    
    
    /**
     * Build a Map of PropertyDescriptor for a given class
     * @param klass the target class
     * @return a Map of PropertyDescriptor for a given class
     * @throws IntrospectionException when trying to introspect the
     */
    public static Map<String, PropertyDescriptor> getProperties(Class<?> klass) throws IntrospectionException{
        final PropertyDescriptor[] properties = Introspector.getBeanInfo(klass).getPropertyDescriptors();        
        return Stream.of(properties).collect(Collectors.toMap(PropertyDescriptor::getDisplayName, Function.identity()));        
    }
    
    public static final int HEADER_ROW_NUM = 0;
    
    
    public void generateExcel(List<SonarRule> rules, List<String> headers, OutputStream output) throws Exception{
        final Map<String, PropertyDescriptor> properties = getProperties(SonarRule.class);
        
        // Remove the fake properties class
        properties.remove("class");
        
        Map<String, PropertyDescriptor> displayedProperties = new LinkedHashMap<>();
        
        if(headers!=null){
            for(String header: headers){
                final PropertyDescriptor descriptor = properties.get(header);
                //if(descriptor!=null){
                    // Only add a descriptor if the required column was found
                    displayedProperties.put(header, descriptor);
                //}
            }
        }
        
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            
            final XSSFCreationHelper createHelper = workbook.getCreationHelper();
            final XSSFCellStyle cellStyle = workbook.createCellStyle();
            cellStyle.setDataFormat(createHelper.createDataFormat().getFormat("yyyy-mm-dd"));
            
            final XSSFSheet sheet = (XSSFSheet) workbook.createSheet("Rules");
            
            int rowIndex = HEADER_ROW_NUM;
            final XSSFRow header = sheet.createRow(rowIndex++);
            int columnIndex = 0;
            for(String v : displayedProperties.keySet()){
                header.createCell(columnIndex++).setCellValue(v);
            }
            
            for(SonarRule rule : rules){
                columnIndex = 0;
                final XSSFRow currentRow = sheet.createRow(rowIndex++);
                for(String property : displayedProperties.keySet()){
                    final Object value = getBeanProperty(rule,displayedProperties,property);
                    final XSSFCell cell = currentRow.createCell(columnIndex++);
                    if(value instanceof Date){
                        cell.setCellStyle(cellStyle);
                        cell.setCellValue((Date)value);
                    }else{
                        cell.setCellValue(value != null ? value.toString() : null);
                    }                           
                            
                }
            }
            
            workbook.write(output);
        }
        
    }

    public static final String DEFAULT_FILE_SUFFIX = ".xlsx";
    public static final String DEFAULT_FILE_PREFIX = "extract-";
    
    public void generateExcel(List<SonarRule> rules) throws Exception{
        
        // Build the target path, either the indicated file or a file in the 
        Path targetPath = getOutputPath();
        if (Files.isDirectory(targetPath)){
         targetPath = Files.createTempFile(targetPath,DEFAULT_FILE_PREFIX,"-"+getLanguage()+DEFAULT_FILE_SUFFIX);
        }
        LOGGER.info(String.format("Generating file %s", targetPath));
        try(OutputStream out = Files.newOutputStream(targetPath, StandardOpenOption.CREATE)){
            generateExcel(rules, getHeaders(), out);
        }        
    }
    
    public void generateExcelTable(List<SonarRule> rules, List<String> headers, OutputStream output) throws Exception{
        final Map<String, PropertyDescriptor> properties = getProperties(SonarRule.class);
        
        // Remove the fake properties class
        properties.remove("class");
        
        Map<String, PropertyDescriptor> displayedProperties = new LinkedHashMap<>();
        
        if(headers!=null){
            for(String header: headers){
                final PropertyDescriptor descriptor = properties.get(header);
                //if(descriptor!=null){
                    // Only add a descriptor if the required column was found
                    displayedProperties.put(header, descriptor);
                //}
            }
        }
        
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            
            final XSSFCreationHelper createHelper = workbook.getCreationHelper();
            final XSSFCellStyle cellStyle = workbook.createCellStyle();
            cellStyle.setDataFormat(createHelper.createDataFormat().getFormat("yyyy-mm-dd"));
            
            final XSSFSheet sheet = (XSSFSheet) workbook.createSheet("Rules");
            
            //Create 
            final XSSFTable table = sheet.createTable();
            table.setDisplayName("Rules");       
            final CTTable cttable = table.getCTTable();

            //Style configurations
            final CTTableStyleInfo style = cttable.addNewTableStyleInfo();
            style.setName("TableStyleMedium2");
            style.setShowColumnStripes(false);
            style.setShowRowStripes(true);

            //Set which area the table should be placed in
            final AreaReference reference = new AreaReference(new CellReference(0, 0), 
                    new CellReference(rules.size(),displayedProperties.size()-1));
            cttable.setRef(reference.formatAsString());
            cttable.setId(1);
            cttable.setName("Rules");
            cttable.setTotalsRowCount(rules.size()+1);
            //cttable.setTotalsRowCount(1);
            cttable.setHeaderRowCount(1);

            final CTTableColumns columns = cttable.addNewTableColumns();
            columns.setCount(displayedProperties.size());
            
            int columnIndex = 1;
            for(String v : displayedProperties.keySet()){
                CTTableColumn column = columns.addNewTableColumn();
                column.setName(v);
                column.setId(columnIndex++);
            }
            
            int rowIndex = HEADER_ROW_NUM;
            final XSSFRow header = sheet.createRow(rowIndex++);
            columnIndex = 0;
            for(String v : displayedProperties.keySet()){
                header.createCell(columnIndex++).setCellValue(v);
            }
            
            for(SonarRule rule : rules){
                columnIndex = 0;
                final XSSFRow currentRow = sheet.createRow(rowIndex++);
                for(String property : displayedProperties.keySet()){
                    final Object value = getBeanProperty(rule,displayedProperties,property);
                    final XSSFCell cell = currentRow.createCell(columnIndex++);
                    if(value instanceof Date){
                        cell.setCellStyle(cellStyle);
                        cell.setCellValue((Date)value);
                    }else{
                        cell.setCellValue(value != null ? value.toString() : null);
                    }                           
                            
                }
            }
            
            workbook.write(output);
        }
        
    }

    
    public static Object getBeanProperty(Object bean, Map<String, PropertyDescriptor> propertiesCache, String property) throws IllegalAccessException, InvocationTargetException{        
        final PropertyDescriptor descriptor = propertiesCache.get(property);        
        return descriptor !=null ? descriptor.getReadMethod().invoke(bean) : null;
    }

    /**
     * @return the language
     */
    public String getLanguage() {
        return language;
    }

    /**
     * @return the searchUri
     */
    public String getSearchUri() {
        return searchUri;
    }

    /**
     * @return the localDate
     */
    public LocalDate getLocalDate() {
        return localDate;
    }

    /**
     * @return the outputPath
     */
    public Path getOutputPath() {
        return outputPath;
    }

    /**
     * @return the headers
     */
    public List<String> getHeaders() {
        return headers;
    }

    /**
     * @param headers the headers to set
     */
    public void setHeaders(List<String> headers) {
        this.headers = headers;
    }
    
    static class PathConverter implements IStringConverter<Path>{

        @Override
        public Path convert(String value) {
            return Paths.get(value);
        }
        
    }
    
    static class LocalDateConverter implements IStringConverter<LocalDate>{

        @Override
        public LocalDate convert(String value) {
            return LocalDate.parse(value);
        }
        
    }

    
    
}
