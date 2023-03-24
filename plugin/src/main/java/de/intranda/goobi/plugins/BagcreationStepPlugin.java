package de.intranda.goobi.plugins;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

/**
 * This file is part of a plugin for Goobi - a Workflow tool for the support of mass digitization.
 *
 * Visit the websites for more information.
 *          - https://goobi.io
 *          - https://www.intranda.com
 *          - https://github.com/intranda/goobi
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 */

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Project;
import org.goobi.beans.ProjectFileGroup;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.export.download.ExportMets;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.XmlTools;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.Prefs;
import ugh.dl.VirtualFileGroup;
import ugh.exceptions.UGHException;
import ugh.fileformats.mets.MetsModsImportExport;

@PluginImplementation
@Log4j2
public class BagcreationStepPlugin extends ExportMets implements IStepPluginVersion2 {

    private static final Namespace metsNamespace = Namespace.getNamespace("mets", "http://www.loc.gov/METS/");
    private static final Namespace modsNamespace = Namespace.getNamespace("mods", "http://www.loc.gov/mods/v3");
    private static final Namespace sipNamespace = Namespace.getNamespace("sip", "https://DILCIS.eu/XML/METS/SIPExtensionMETS");
    private static final Namespace csipNamespace = Namespace.getNamespace("csip", "https://DILCIS.eu/XML/METS/CSIPExtensionMETS");
    private static final Namespace xlinkNamespace = Namespace.getNamespace("xlink", "http://www.w3.org/1999/xlink");
    private static final Namespace dvNamespace = Namespace.getNamespace("dv", "http://dfg-viewer.de/");
    private static final Namespace xsiNamespace = Namespace.getNamespace("xsi", "http://www.w3.org/2001/XMLSchema-instance");

    private static final long serialVersionUID = 211912948222450125L;
    @Getter
    private String title = "intranda_step_bagcreation";
    @Getter
    private Step step;

    private Process process;

    private Project project;

    private Prefs prefs;

    @Getter
    private String value;
    @Getter
    private String returnPath;

    @Getter
    private transient Path tempfolder;

    private List<ProjectFileGroup> filegroups = new ArrayList<>();

    // organisation name
    private String userAgent;

    // rights information
    private String rightsOwner;
    private String rightsOwnerLogo;
    private String rightsOwnerSiteURL;
    private String rightsOwnerContact;
    private String metsRightsLicense;
    private String metsRightsSponsor;
    private String metsRightsSponsorLogo;
    private String metsRightsSponsorSiteURL;

    // links to presentation
    private String digiprovPresentation;
    private String digiprovPresentationAnchor;

    // links to anchor/child file
    private String digiprovReference;
    private String digiprovReferenceAnchor;
    // urls
    private String iiifUrl;
    private String sruUrl;

    @Override
    public void initialize(Step step, String returnPath) {
        this.returnPath = returnPath;
        this.step = step;
        process = step.getProzess();
        project = process.getProjekt();
        prefs = process.getRegelsatz().getPreferences();

        // read parameters from correct block in configuration file
        SubnodeConfiguration myconfig = ConfigPlugins.getProjectAndStepConfig(title, step);
        List<HierarchicalConfiguration> grps = myconfig.configurationsAt("/group");
        for (HierarchicalConfiguration hc : grps) {
            ProjectFileGroup group = new ProjectFileGroup();
            group.setName(hc.getString("@fileGrpName"));
            group.setPath(hc.getString("@prefix"));
            group.setMimetype(hc.getString("@mimeType"));
            group.setSuffix(hc.getString("@suffix"));
            group.setFolder(hc.getString("@folder"));
            filegroups.add(group);
        }
        userAgent = myconfig.getString("/userAgent", "");

        rightsOwner = myconfig.getString("/rightsOwner", "");
        rightsOwnerLogo = myconfig.getString("/rightsOwnerLogo", "");
        rightsOwnerSiteURL = myconfig.getString("/rightsOwnerSiteURL", "");
        rightsOwnerContact = myconfig.getString("/rightsOwnerContact", "");
        metsRightsLicense = myconfig.getString("/metsRightsLicense", "");
        metsRightsSponsor = myconfig.getString("/metsRightsSponsor", "");
        metsRightsSponsorLogo = myconfig.getString("/metsRightsSponsorLogo", "");
        metsRightsSponsorSiteURL = myconfig.getString("/metsRightsSponsorSiteURL", "");

        digiprovPresentation = myconfig.getString("/digiprovPresentation", "");
        digiprovPresentationAnchor = myconfig.getString("/digiprovPresentationAnchor", "");
        digiprovReference = myconfig.getString("/digiprovReference", "");
        digiprovReferenceAnchor = myconfig.getString("/digiprovReferenceAnchor", "");
        iiifUrl = myconfig.getString("/iiifUrl", "");
        sruUrl = myconfig.getString("/sruUrl", "");
    }

    @Override
    public PluginReturnValue run() {
        String identifier = null;
        VariableReplacer vp = null;

        tempfolder = Paths.get(ConfigurationHelper.getInstance().getTemporaryFolder(), UUID.randomUUID().toString());

        try {
            StorageProvider.getInstance().createDirectories(tempfolder);
        } catch (IOException e) {
            log.error(e);
            return PluginReturnValue.ERROR;
        }
        Map<String, List<Path>> files = new HashMap<>();
        try {
            // read metadata
            Fileformat fileformat = process.readMetadataFile();

            // find doi metadata
            DocStruct ds = fileformat.getDigitalDocument().getLogicalDocStruct();
            if (ds.getType().isAnchor()) {
                ds = ds.getAllChildren().get(0);
            }
            for (Metadata md : ds.getAllMetadata()) {
                if ("DOI".equals(md.getType().getName())) {
                    identifier = md.getValue();
                    break;
                }
            }
            if (identifier == null) {
                // no doi found, cancel export
                return PluginReturnValue.ERROR;
            }
            vp = new VariableReplacer(fileformat.getDigitalDocument(), prefs, process, null);
            // create export file

            MetsModsImportExport exportFilefoExport = new MetsModsImportExport(prefs);
            exportFilefoExport.setDigitalDocument(fileformat.getDigitalDocument());
            exportFilefoExport.setWriteLocal(false);
            exportFilefoExport.getDigitalDocument().addAllContentFiles();

            // write process id as metadata
            Metadata processid = new Metadata(prefs.getMetadataTypeByName("_PROCESSID"));
            processid.setValue(String.valueOf(process.getId()));
            ds.addMetadata(processid);

            // generate uuids
            exportFilefoExport.setCreateUUIDs(true);

            // create filegroups for each folder/representation
            for (ProjectFileGroup projectFileGroup : filegroups) {
                // check if folder exists
                Path sourceFolder = getSourceFolder(projectFileGroup.getFolder());

                if (sourceFolder != null && StorageProvider.getInstance().isFileExists(sourceFolder)) {
                    files.put(projectFileGroup.getName(), StorageProvider.getInstance().listFiles(sourceFolder.toString()));
                    // generate filegroup
                    VirtualFileGroup virt = new VirtualFileGroup(projectFileGroup.getName(), projectFileGroup.getPath(),
                            projectFileGroup.getMimetype(), projectFileGroup.getSuffix());
                    exportFilefoExport.getDigitalDocument().getFileSet().addVirtualFileGroup(virt);
                }
            }

            // project parameter
            setProjectParameter(identifier, vp, exportFilefoExport);

            // save file
            exportFilefoExport.write(tempfolder.toString() + "/METS.xml");
        } catch (UGHException | IOException | SwapException e) {
            log.error(e);
        }
        // open exported file to enhance it
        try {
            Document doc = XmlTools.getSAXBuilder().build(tempfolder.toString() + "/METS.xml");
            Element mets = doc.getRootElement();

            mets.addNamespaceDeclaration(sipNamespace);
            mets.addNamespaceDeclaration(csipNamespace);
            mets.setAttribute("TYPE", "Mixed"); // CSIP2
            mets.setAttribute("PROFILE", "https://earkcsip.dilcis.eu/profile/E-ARK-CSIP.xml"); // SIP2
            mets.setAttribute("CONTENTINFORMATIONTYPE", "MIXED", csipNamespace); // CSIP4

            // enhance existing agent, add additional user agent for submitting agent (SIP4 - SIP 31)
            String creationDate = createUserAgent(mets);

            // enhance dmdSecs
            String dmdIds = changeDmdSecs(mets, creationDate);

            String amdId = changeAmdSec(mets, creationDate);

            createFileChecksums(files, mets);

            changeStructMap(mets, identifier, dmdIds, amdId);

            removeStructLinks(mets);

            // save enhanced file
            XMLOutputter xmlOut = new XMLOutputter(Format.getPrettyFormat());
            FileOutputStream fos = new FileOutputStream(tempfolder.toString() + "/METS.xml");
            xmlOut.output(doc, fos);
            fos.close();

        } catch (JDOMException | IOException e) {
            log.error(e);
        }

        return PluginReturnValue.FINISH;
    }

    private void removeStructLinks(Element mets) {
        mets.removeChild("structLink", metsNamespace);
    }

    private void changeStructMap(Element mets, String identifier, String dmdIds, String amdId) {
        List<Element> structMaps = mets.getChildren("structMap", metsNamespace);
        for (Element structMap : structMaps) {
            if ("PHYSICAL".equals(structMap.getAttributeValue("TYPE"))) {
                structMap.setAttribute("LABEL", "CSIP"); // CSIP82
                structMap.setAttribute("ID", "uuid-" + UUID.randomUUID().toString()); // CSIP83
                Element physSequence = structMap.getChild("div", metsNamespace); // CSIP84
                physSequence.setAttribute("LABEL", identifier);
                physSequence.removeAttribute("TYPE");

                physSequence.removeContent();

                Element metadataDiv = new Element("div", metsNamespace); // CSIP88
                metadataDiv.setAttribute("LABEL", "Metadata"); // CSIP88
                metadataDiv.setAttribute("ID", "uuid-" + UUID.randomUUID().toString());
                metadataDiv.setAttribute("DMDID", dmdIds);
                metadataDiv.setAttribute("ADMID", amdId);

                physSequence.addContent(metadataDiv);

                // create a div lement for each fileGroup
                Element fileSec = mets.getChild("fileSec", metsNamespace);
                for (Element fileGrp : fileSec.getChildren()) {
                    String fileGroupId = fileGrp.getAttributeValue("ID");
                    String fileGroupLabel = fileGrp.getAttributeValue("USE");
                    String href = fileGroupLabel.toLowerCase() + "/METS.xml";

                    Element div = new Element("div", metsNamespace);
                    div.setAttribute("ID", "uuid-" + UUID.randomUUID().toString());
                    div.setAttribute("LABEL", fileGroupLabel);
                    physSequence.addContent(div);

                    Element mptr = new Element("mptr", metsNamespace);
                    mptr.setAttribute("type", "simple", xlinkNamespace);
                    mptr.setAttribute("href", href, xlinkNamespace);
                    mptr.setAttribute("title", fileGroupId, xlinkNamespace);
                    mptr.setAttribute("LOCTYPE", "URL");
                    div.addContent(mptr);
                }
            }
        }
    }

    private String changeAmdSec(Element mets, String creationDate) {

        Element amdSec = mets.getChild("amdSec", metsNamespace); // CSIP31
        String id = "";
        if (amdSec != null) {
            id = amdSec.getAttributeValue("ID");

            Element digiprovMD = amdSec.getChild("digiprovMD", metsNamespace);
            if (digiprovMD != null) {
                digiprovMD.setAttribute("STATUS", "CURRENT"); // CSIP34
                digiprovMD.setAttribute("CREATED", creationDate);
                // generate separate files for content, create link to the file with mdRef // CSIP35 - CSIP44
                Element mdWrap = digiprovMD.getChild("mdWrap", metsNamespace);
                Element xmlData = mdWrap.getChild("xmlData", metsNamespace);
                Element links = xmlData.getChild("links", dvNamespace);
                // create deep copy
                Element copy = links.clone();
                try {
                    Element mdRef = createFile(copy, "metadata/other/", "DIGIPROV", "");
                    mdRef.setAttribute("MDTYPE", "OTHER");
                    mdRef.setAttribute("OTHERMDTYPE", "DVLINKS");

                    digiprovMD.removeContent(mdWrap);
                    digiprovMD.addContent(mdRef);
                } catch (IOException e) {
                    log.error(e);
                }
            }

            Element rightsMD = amdSec.getChild("rightsMD", metsNamespace);
            if (rightsMD != null) {
                rightsMD.setAttribute("STATUS", "CURRENT"); // CSIP47
                rightsMD.setAttribute("CREATED", creationDate);

                // generate separate files for content, create link to the file with mdRef // CSIP48 - CSIP57
                Element mdWrap = rightsMD.getChild("mdWrap", metsNamespace);
                Element xmlData = mdWrap.getChild("xmlData", metsNamespace);
                Element rights = xmlData.getChild("rights", dvNamespace);
                // create deep copy
                Element copy = rights.clone();
                try {
                    Element mdRef = createFile(copy, "metadata/other/", "DVRIGHTS", "");
                    mdRef.setAttribute("MDTYPE", "OTHER");
                    mdRef.setAttribute("OTHERMDTYPE", "DVRIGHTS");
                    rightsMD.removeContent(mdWrap);
                    rightsMD.addContent(mdRef);
                } catch (IOException e) {
                    log.error(e);
                }
            }
        }
        return id;
    }

    private String changeDmdSecs(Element mets, String creationDate) {
        StringBuilder ids = new StringBuilder();

        List<Element> dmdSecs = mets.getChildren("dmdSec", metsNamespace);
        for (Element dmdSec : dmdSecs) {
            String dmdSecId = dmdSec.getAttributeValue("ID");
            if (ids.length() > 0) {
                ids.append(" ");
            }
            ids.append(dmdSecId);

            dmdSec.setAttribute("CREATED", creationDate); // CSIP19
            dmdSec.setAttribute("STATUS", "CURRENT"); // CSIP20

            // get mods element
            Element mdWrap = dmdSec.getChild("mdWrap", metsNamespace);
            Element xmlData = mdWrap.getChild("xmlData", metsNamespace);
            Element mods = xmlData.getChild("mods", modsNamespace);

            // create deep copy
            Element copy = mods.clone();
            try {
                // create external file, remove content from dmdSec, add file reference to dmdSec,
                Element mdRef = createFile(copy, "metadata/descriptive/", dmdSecId,
                        "http://www.loc.gov/mods/v3 http://www.loc.gov/standards/mods/v3/mods-3-7.xsd");
                dmdSec.removeContent(mdWrap);
                dmdSec.addContent(mdRef);
            } catch (IOException e) {
                log.error(e);
            }
        }
        return ids.toString();
    }

    private Element createFile(Element root, String subFolder, String filename, String schemaLocation) throws IOException {
        if (StringUtils.isNotBlank(schemaLocation)) {
            root.addNamespaceDeclaration(xsiNamespace);
            root.setAttribute("schemaLocation", schemaLocation, xsiNamespace);
        }

        Document doc = new Document();
        doc.setRootElement(root);
        Path fileName = null;
        if (StringUtils.isNotBlank(subFolder)) {
            fileName = Paths.get(tempfolder.toString(), subFolder, filename + ".xml");
            StorageProvider.getInstance().createDirectories(fileName.getParent());
        } else {
            fileName = Paths.get(tempfolder.toString(), filename + ".xml");
        }

        XMLOutputter xmlOut = new XMLOutputter(Format.getPrettyFormat());
        FileOutputStream fos = new FileOutputStream(fileName.toString());
        xmlOut.output(doc, fos);
        fos.close();

        Element mdRef = new Element("mdRef", metsNamespace);
        mdRef.setAttribute("ID", "uuid-" + UUID.randomUUID().toString());
        mdRef.setAttribute("LOCTYPE", "URL");
        mdRef.setAttribute("MDTYPE", "MODS");
        mdRef.setAttribute("MIMETYPE", "text/xml");
        mdRef.setAttribute("CHECKSUMTYPE", "SHA-256");
        mdRef.setAttribute("type", "simple", xlinkNamespace);
        mdRef.setAttribute("href", subFolder + filename + "xml", xlinkNamespace);

        mdRef.setAttribute("SIZE", "" + StorageProvider.getInstance().getFileSize(fileName));
        mdRef.setAttribute("CREATED", StorageProvider.getInstance().getFileCreationTime(fileName));
        mdRef.setAttribute("CHECKSUM", StorageProvider.getInstance().createSha256Checksum(fileName));

        return mdRef;

    }

    private String createUserAgent(Element mets) {
        Element metsHdr = mets.getChild("metsHdr", metsNamespace);
        metsHdr.setAttribute("OAISPACKAGETYPE", "SIP", csipNamespace); // SIP4
        metsHdr.setAttribute("RECORDSTATUS", "NEW"); // SIP3
        String creationDate = metsHdr.getAttributeValue("CREATEDATE");
        Element agent = metsHdr.getChild("agent", metsNamespace);

        Element note = agent.getChild("note", metsNamespace);
        note.setAttribute("NOTETYPE", "IDENTIFICATIONCODE", csipNamespace); // SIP14

        Element agent2 = new Element("agent", metsNamespace);
        agent2.setAttribute("ROLE", "CREATOR"); // SIP16
        agent2.setAttribute("TYPE", "ORGANIZATION"); // SIP17
        metsHdr.addContent(agent2);
        Element name2 = new Element("name", metsNamespace);
        name2.setText(userAgent); // SIP24
        agent2.addContent(name2);
        Element note2 = new Element("note", metsNamespace);
        note2.setAttribute("NOTETYPE", "IDENTIFICATIONCODE", csipNamespace); // SIP20
        note2.setText("1");
        agent2.addContent(note2);
        return creationDate;
    }

    private void createFileChecksums(Map<String, List<Path>> files, Element mets) throws IOException {
        Element fileSec = mets.getChild("fileSec", metsNamespace);
        fileSec.setAttribute("ID", "uuid-" + UUID.randomUUID().toString()); // CSIP59

        for (Element fileGrp : fileSec.getChildren("fileGrp", metsNamespace)) {
            fileGrp.setAttribute("ID", "uuid-" + UUID.randomUUID().toString());

            String name = fileGrp.getAttributeValue("USE");
            List<Path> filesInFolder = files.get(name);
            List<Element> filesInXml = fileGrp.getChildren("file", metsNamespace);
            for (int i = 0; i < filesInXml.size(); i++) {
                Element fileElement = filesInXml.get(i);
                Path file = filesInFolder.get(i);
                // checksum, filesize, changedate

                fileElement.setAttribute("SIZE", "" + StorageProvider.getInstance().getFileSize(file)); // CSIP69
                fileElement.setAttribute("CREATED", StorageProvider.getInstance().getFileCreationTime(file)); // CSIP70
                fileElement.setAttribute("CHECKSUM", StorageProvider.getInstance().createSha256Checksum(file)); // CSIP71
                fileElement.setAttribute("CHECKSUMTYPE", "SHA-256"); // CSIP72
                Element flocat = fileElement.getChild("FLocat", metsNamespace);
                flocat.setAttribute("type", "simple", xlinkNamespace); // CSIP78
            }
        }

        // create separate file for each fileGrp, create link to the file with mdRef (CSIP76 - SIP35)
        for (Element fileGrp : fileSec.getChildren("fileGrp", metsNamespace)) {

            Element clone = fileGrp.clone();

            Element file = createFileGroupFile(clone);

            fileGrp.removeContent();
            fileGrp.addContent(file);
        }

    }

    private Element createFileGroupFile(Element root) throws IOException {
        String name = root.getAttributeValue("USE");

        Document doc = new Document();
        doc.setRootElement(root);
        Path fileName = null;
        fileName = Paths.get(tempfolder.toString(), name.toLowerCase(), "METS.xml");
        StorageProvider.getInstance().createDirectories(fileName.getParent());

        XMLOutputter xmlOut = new XMLOutputter(Format.getPrettyFormat());
        FileOutputStream fos = new FileOutputStream(fileName.toString());
        xmlOut.output(doc, fos);
        fos.close();

        Element file = new Element("file", metsNamespace);

        file.setAttribute("ID", "uuid-" + UUID.randomUUID().toString());
        file.setAttribute("MIMETYPE", "text/xml");
        file.setAttribute("SIZE", "" + StorageProvider.getInstance().getFileSize(fileName));
        file.setAttribute("CREATED", StorageProvider.getInstance().getFileCreationTime(fileName));
        file.setAttribute("CHECKSUM", StorageProvider.getInstance().createSha256Checksum(fileName));
        file.setAttribute("CHECKSUMTYPE", "SHA-256");

        Element flocat = new Element("FLocat", metsNamespace);
        flocat.setAttribute("type", "simple", xlinkNamespace);
        flocat.setAttribute("href", name.toLowerCase() + "/METS.xml", xlinkNamespace);
        flocat.setAttribute("LOCTYPE", "URL");
        file.addContent(flocat);
        return file;
    }

    private void setProjectParameter(String identifier, VariableReplacer vp, MetsModsImportExport exportFilefoExport) {
        exportFilefoExport.setRightsOwner(vp.replace(rightsOwner));
        exportFilefoExport.setRightsOwnerLogo(vp.replace(rightsOwnerLogo));
        exportFilefoExport.setRightsOwnerSiteURL(vp.replace(rightsOwnerSiteURL));
        exportFilefoExport.setRightsOwnerContact(vp.replace(rightsOwnerContact));
        exportFilefoExport.setDigiprovPresentation(vp.replace(digiprovPresentation));
        exportFilefoExport.setDigiprovReference(vp.replace(digiprovReference));
        exportFilefoExport.setDigiprovPresentationAnchor(vp.replace(digiprovPresentationAnchor));
        exportFilefoExport.setDigiprovReferenceAnchor(vp.replace(digiprovReferenceAnchor));

        exportFilefoExport.setMetsRightsLicense(vp.replace(metsRightsLicense));
        exportFilefoExport.setMetsRightsSponsor(vp.replace(metsRightsSponsor));
        exportFilefoExport.setMetsRightsSponsorLogo(vp.replace(metsRightsSponsorLogo));
        exportFilefoExport.setMetsRightsSponsorSiteURL(vp.replace(metsRightsSponsorSiteURL));

        exportFilefoExport.setIIIFUrl(vp.replace(iiifUrl));
        exportFilefoExport.setSruUrl(vp.replace(sruUrl));

        // mets pointer between anchor and volume
        String pointer = project.getMetsPointerPath();
        pointer = vp.replace(pointer);
        exportFilefoExport.setMptrUrl(pointer);

        String anchor = project.getMetsPointerPathAnchor();
        pointer = vp.replace(anchor);
        exportFilefoExport.setMptrAnchorUrl(pointer);

        // obj id -> DOI
        exportFilefoExport.setGoobiID(identifier);
    }

    private Path getSourceFolder(String folder) {
        Path sourceFolder = null;
        try {
            switch (folder) {
                case "master":
                    sourceFolder = Paths.get(process.getImagesOrigDirectory(false));
                    break;
                case "alto":
                    sourceFolder = Paths.get(process.getOcrAltoDirectory());
                    break;
                case "xml":
                    sourceFolder = Paths.get(process.getOcrXmlDirectory());
                    break;
                case "txt":
                    sourceFolder = Paths.get(process.getOcrTxtDirectory());
                    break;
                case "pdf":
                    sourceFolder = Paths.get(process.getOcrPdfDirectory());
                    break;
                default:
                    sourceFolder = Paths.get(process.getConfiguredImageFolder(folder));
                    break;
            }
        } catch (IOException | SwapException | DAOException e) {
            log.error(e);
        }
        return sourceFolder;
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.NONE;
    }

    @Override
    public String getPagePath() {
        return null;
    }

    @Override
    public PluginType getType() {
        return PluginType.Step;
    }

    @Override
    public String cancel() {
        return "/uii" + returnPath;
    }

    @Override
    public String finish() {
        return "/uii" + returnPath;
    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null; // NOSONAR
    }

    @Override
    public boolean execute() {
        PluginReturnValue ret = run();
        return ret != PluginReturnValue.ERROR;
    }

}
