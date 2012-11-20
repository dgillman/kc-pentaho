package com.rsmart.kuali.coeus.reporting;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Connection;
import java.sql.SQLException;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.pentaho.reporting.engine.classic.core.ClassicEngineBoot;
import org.pentaho.reporting.engine.classic.core.MasterReport;
import org.pentaho.reporting.engine.classic.core.ReportProcessingException;
import org.pentaho.reporting.engine.classic.core.layout.output.ReportProcessor;
import org.pentaho.reporting.engine.classic.core.DataFactory;
import org.pentaho.reporting.engine.classic.core.modules.misc.datafactory.sql.SQLReportDataFactory;
import org.pentaho.reporting.engine.classic.core.modules.output.pageable.base.PageableReportProcessor;
import org.pentaho.reporting.engine.classic.core.modules.output.pageable.pdf.PdfOutputProcessor;
import org.pentaho.reporting.engine.classic.core.modules.output.table.base.FlowReportProcessor;
import org.pentaho.reporting.engine.classic.core.modules.output.table.base.StreamReportProcessor;
import org.pentaho.reporting.engine.classic.core.modules.output.table.html.AllItemsHtmlPrinter;
import org.pentaho.reporting.engine.classic.core.modules.output.table.html.FileSystemURLRewriter;
import org.pentaho.reporting.engine.classic.core.modules.output.table.html.HtmlOutputProcessor;
import org.pentaho.reporting.engine.classic.core.modules.output.table.html.HtmlPrinter;
import org.pentaho.reporting.engine.classic.core.modules.output.table.html.StreamHtmlOutputProcessor;
import org.pentaho.reporting.engine.classic.core.modules.output.table.xls.FlowExcelOutputProcessor;
import org.pentaho.reporting.libraries.repository.ContentLocation;
import org.pentaho.reporting.libraries.repository.DefaultNameGenerator;
import org.pentaho.reporting.libraries.repository.stream.StreamRepository;
import org.pentaho.reporting.libraries.resourceloader.Resource;
import org.pentaho.reporting.libraries.resourceloader.ResourceException;
import org.pentaho.reporting.libraries.resourceloader.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

public class ReportingServlet extends HttpServlet {

  private static final Logger LOG = LoggerFactory.getLogger(ReportingServlet.class);
  
  // Servlet InitParam that should identify the path to the report definition files
  // relative to the root directory of the ServletContext (eg. directory above WEB-INF)
  private static final String INIT_PARAM_REPORT_PATH  = "reportPath";
  
  // Names of the HTTP params that may come with a GET request
  private static final String HTTP_PARAM_TYPE = "type";
  private static final String HTTP_PARAM_REPORT = "report";
  
  // Identifies the Spring bean that is the DB DataSource
  private static final String SPRING_DATASOURCE_NAME = "dataSource";
  
  // MIME types that may be returned based on selected OutputType
  private static final String MIMETYPE_PDF = "application/pdf";
  private static final String MIMETYPE_HTML = "text/html";
  private static final String MIMETYPE_XLS = "application/vnd.ms-excel";
  
  enum OutputType {
    HTML, PDF, XLS
  }

  private WebApplicationContext applicationContext = null;
  private DataSource datasource = null;
  private String reportPath = "";
  
  public ReportingServlet() {
    super();
  }

  public void init(ServletConfig config) throws ServletException {
    super.init(config);
    
    // Initialize the Reporting Engine
    ClassicEngineBoot.getInstance().start();

    // Get the Spring application context
    applicationContext = WebApplicationContextUtils.getWebApplicationContext(config.getServletContext());
    
    // Get the DataSource
    datasource = (DataSource) applicationContext.getBean(SPRING_DATASOURCE_NAME);
    
    // directory containing the report definitions
    final String path = config.getInitParameter(INIT_PARAM_REPORT_PATH);
    
    // add a '/' character if it is needed
    if (path != null) {
      final String tmp = path.trim();
      final int len = tmp.length();
      
      if (len > 0 && path.charAt(len-1) != File.separatorChar) {
        reportPath = tmp + File.separatorChar;
      } else {
        reportPath = path;
      }
    }

    LOG.debug("Path for report definitions: {}", reportPath);
    
    // check that the reports directory exists and has appropriate permissions
    final File reportDir = new File(config.getServletContext().getRealPath(reportPath));
    
    if (!(reportDir != null && reportDir.isDirectory() && reportDir.canRead())) {
      LOG.error("Cannot read report directory: {}", reportDir.getAbsolutePath());
      throw new IllegalStateException ("init parameter " + 
         INIT_PARAM_REPORT_PATH + " resolves to " + reportDir.getAbsolutePath() + " which is not a readable directory");
    }
    
    LOG.info("ReportingServlet initialized");
  }

  /**
   * Returns a database connection.
   * 
   * @return DB connection
   * @throws SQLException
   */
  protected final Connection getConnection() throws SQLException {
    return datasource.getConnection();
  }
  
  /**
   * Converts the relative path for reports to an absolute path.
   * 
   * @param reportFile
   * @return
   */
  protected final String getAbsoluteReportPath(final String reportFile) {
    final String filePath = reportPath + reportFile;
    return getServletContext().getRealPath(filePath);
  }
  
  /**
   * Returns the Pentaho MasterReport object representing the report definition.
   * 
   * @param reportFile
   * @return
   * @throws MalformedURLException
   * @throws ResourceException
   */
  protected MasterReport getMasterReport(final String reportFile) throws MalformedURLException, 
     ResourceException {
    final ResourceManager manager = new ResourceManager();

    manager.registerDefaults();
    
    final URL reportURL = new URL("file:" + getAbsoluteReportPath(reportFile));
    Resource res = manager.createDirectly(reportURL, MasterReport.class);
    
    return (MasterReport) res.getResource();
  }
  
  /**
   * Returns the Pentaho DataFactory object which wraps access to the database.
   * 
   * @return
   * @throws SQLException
   */
  public DataFactory getDataFactory() throws SQLException
  {
    final SQLReportDataFactory dataFactory = new SQLReportDataFactory(getConnection());

    /*
     * TODO: report files and associated queries should be defined in the Spring applicaiton context
     * and read dynamically at runtime.
     * 
     */
    dataFactory.setQuery("awards", "SELECT * FROM AWARD");
    
    return dataFactory;
  }
  
  /**
   * Returns a ReportProcessor object that will generate the correct output based on OutputType.
   * 
   * TODO: Setting of the return type should be moved out of this method - it feels like a cobbled-on
   * side effect here.
   * 
   * @param report
   * @param type
   * @param response
   * @return
   * @throws ReportProcessingException
   * @throws IOException
   */
  protected ReportProcessor getReportProcessor (final MasterReport report, final OutputType type,
     HttpServletResponse response) throws ReportProcessingException, IOException {
    
    final OutputStream outputStream = response.getOutputStream();
    
    switch (type)
    {
      case PDF:
      {
        LOG.debug("producing PDF output");
        response.setContentType(MIMETYPE_PDF);
        final PdfOutputProcessor outputProcessor =
            new PdfOutputProcessor(report.getConfiguration(), outputStream, report.getResourceManager());
        return new PageableReportProcessor(report, outputProcessor);
      }

      case XLS:
      {
        LOG.debug("producing XLS output");
        response.setContentType(MIMETYPE_XLS);
        final FlowExcelOutputProcessor target =
            new FlowExcelOutputProcessor(report.getConfiguration(), outputStream, report.getResourceManager());
        return new FlowReportProcessor(report, target);
      }

      default:
      case HTML:
      {
        LOG.debug("producing HTML output");
        response.setContentType(MIMETYPE_HTML);
        final StreamRepository targetRepository = new StreamRepository(outputStream);
        final ContentLocation targetRoot = targetRepository.getRoot();
        final HtmlOutputProcessor outputProcessor = new StreamHtmlOutputProcessor(report.getConfiguration());
        final HtmlPrinter printer = new AllItemsHtmlPrinter(report.getResourceManager());
        printer.setContentWriter(targetRoot, new DefaultNameGenerator(targetRoot, "index", "html"));
        printer.setDataWriter(null, null);
        printer.setUrlRewriter(new FileSystemURLRewriter());
        outputProcessor.setPrinter(printer);
        return new StreamReportProcessor(report, outputProcessor);
      }
    }
  }

  protected void doGet(HttpServletRequest request,
     HttpServletResponse response) throws ServletException, IOException {
    
    /* 
     * process the params from the request
     *   'type' - use to select output type (PDF, HTML, XLS)
     *   'report' - report definition file
     */
    final String outputType = request.getParameter(HTTP_PARAM_TYPE);
    final String reportFile = request.getParameter(HTTP_PARAM_REPORT);
    OutputType type = null;
    
    LOG.debug("request for report: {}, with format: {}", new Object[] { reportFile, outputType });
    
    if (outputType == null) {
      //default to HTML
      type = OutputType.HTML;
    } else {
      type = OutputType.valueOf(outputType);
    }
    
    if (reportFile == null || reportFile.trim().length() < 1) {
      LOG.error ("'report' argument is missing or invalid");
      response.sendError(400, "'report' argument is missing or invalid");
      return;
    }
    
    try {
      final MasterReport masterReport = getMasterReport(reportFile);
      final DataFactory dataFactory = getDataFactory();
  
      // Set the data factory for the report
      if (dataFactory != null)
      {
        masterReport.setDataFactory(dataFactory);
      }
      
      LOG.debug("processing report");
      final ReportProcessor reportProcessor = getReportProcessor(masterReport, type, response);

      // Generate the report
      reportProcessor.processReport();
      LOG.debug("finished report");
    } catch (Exception e) {
      LOG.error("Error responding to report request. Params: [\"report\": " + reportFile + 
        ", \"type\": " + outputType + "]", e);
      response.sendError(500);
    }
  }

}