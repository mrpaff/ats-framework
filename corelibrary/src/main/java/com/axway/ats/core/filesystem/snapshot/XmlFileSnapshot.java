/*
 * Copyright 2017 Axway Software
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.axway.ats.core.filesystem.snapshot;

import java.io.StringReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.io.SAXReader;

import com.axway.ats.common.filesystem.FileSystemOperationException;
import com.axway.ats.common.filesystem.snapshot.equality.FileSystemEqualityState;
import com.axway.ats.common.filesystem.snapshot.equality.FileTrace;
import com.axway.ats.common.xml.XMLException;
import com.axway.ats.core.filesystem.LocalFileSystemOperations;
import com.axway.ats.core.filesystem.snapshot.matchers.FindRules;
import com.axway.ats.core.filesystem.snapshot.matchers.SkipXmlNodeMatcher;

/**
 * Compares the content of XML files
 */
public class XmlFileSnapshot extends FileSnapshot {

    private static final Logger      log              = Logger.getLogger( FileSnapshot.class );

    private static final long        serialVersionUID = 1L;

    private List<SkipXmlNodeMatcher> matchers         = new ArrayList<>();

    XmlFileSnapshot( SnapshotConfiguration configuration, String path, FindRules fileRule,
                     List<SkipXmlNodeMatcher> fileMatchers ) {
        super( configuration, path, fileRule );

        configuration.setCheckMD5( false );
        configuration.setCheckSize( false );

        if( fileMatchers == null ) {
            fileMatchers = new ArrayList<SkipXmlNodeMatcher>();
        }
        for( SkipXmlNodeMatcher matcher : fileMatchers ) {
            this.matchers.add( matcher );
        }
    }

    XmlFileSnapshot( String path, long size, long timeModified, String md5, String permissions ) {
        super( path, size, timeModified, md5, permissions );

        if( configuration != null ) {
            configuration.setCheckMD5( false );
            configuration.setCheckSize( false );
        }
    }

    /**
     * Used to extend a regular file snapshot instance to the wider XML snapshot instance.
     * It adds all content check matchers
     * 
     * @param fileSnapshot the instance to extend
     * @return the extended instance
     */
    XmlFileSnapshot getNewInstance( FileSnapshot fileSnapshot ) {

        XmlFileSnapshot instance = new XmlFileSnapshot( fileSnapshot.path, fileSnapshot.size,
                                                        fileSnapshot.timeModified, fileSnapshot.md5,
                                                        fileSnapshot.permissions );
        instance.matchers = this.matchers;

        return instance;
    }

    @Override
    void compare( FileSnapshot that, FileSystemEqualityState equality, FileTrace fileTrace ) {

        // first compare the regular file attributes
        fileTrace = super.compareFileAttributes( that, fileTrace, true );

        // now compare the files content

        // load the files
        XmlNode thisXmlNode = loadXmlFile( equality.getFirstAtsAgent(), this.getPath() );
        XmlNode thatXmlNode = loadXmlFile( equality.getSecondAtsAgent(), that.getPath() );

        // we currently call all matchers on both files,
        // so it does not matter if a matcher is provided for first or second snapshot
        for( SkipXmlNodeMatcher matcher : this.matchers ) {
            matcher.process( fileTrace.getFirstSnapshot(), thisXmlNode );
            matcher.process( fileTrace.getSecondSnapshot(), thatXmlNode );
        }
        for( SkipXmlNodeMatcher matcher : ( ( XmlFileSnapshot ) that ).matchers ) {
            matcher.process( fileTrace.getSecondSnapshot(), thatXmlNode );
            matcher.process( fileTrace.getFirstSnapshot(), thisXmlNode );
        }

        // now compare the rest of the XML nodes
        compareNodes( ( ( XmlFileSnapshot ) that ).matchers, thisXmlNode, thatXmlNode, equality, fileTrace );
        ( ( XmlFileSnapshot ) that ).compareNodes( this.matchers, thisXmlNode, thatXmlNode, equality,
                                                   fileTrace );

        getDifferentNodes( thisXmlNode, fileTrace, false );
        getDifferentNodes( thatXmlNode, fileTrace, true );

        if( fileTrace.hasDifferencies() ) {
            // files are different
            equality.addDifference( fileTrace );
        } else {
            log.debug( "Same files: " + this.getPath() + " and " + that.getPath() );
        }
    }

    private void compareNodes( List<SkipXmlNodeMatcher> thatMatchers, XmlNode thisXmlNode,
                               XmlNode thatXmlNode, FileSystemEqualityState equality, FileTrace fileTrace ) {

        for( XmlNode thisChild : thisXmlNode.getChildren() ) {
            for( XmlNode thatChild : thatXmlNode.getChildren() ) {
                if( !thatChild.isChecked() ) {
                    if( thisChild.getSignature( "" )
                                 .trim()
                                 .equalsIgnoreCase( thatChild.getSignature( "" ).trim() ) ) {
                        // nodes with same signature found, check if they have same content

                        thisChild.setChecked();
                        thatChild.setChecked();

                        if( thisChild.getContent( "" )
                                     .trim()
                                     .equalsIgnoreCase( thatChild.getContent( "" ).trim() ) ) {
                            // nodes and their sub-nodes are same, do not need to dig any deeper
                            thisChild.setCheckedIncludingChildren();
                            thatChild.setCheckedIncludingChildren();
                        } else {
                            // nodes with same signature but different content, dig deeper
                            compareNodes( thatMatchers, thisChild, thatChild, equality, fileTrace );
                        }

                        // go check next children
                        break;
                    } else {
                        // nodes with different signature, go check next children
                    }
                }
            }
        }
    }

    private void getDifferentNodes( XmlNode srcNode, FileTrace fileTrace, boolean areSnapshotsReversed ) {

        for( XmlNode child : srcNode.getChildren() ) {
            if( child.isChecked() ) {
                if( child.getDifferenceDescription() != null ) {
                    fileTrace.addDifference( child.getDifferenceDescription(),
                                             "\n\t" + child.getThisDifferenceValue(),
                                             child.getThatDifferenceValue() );
                } else {
                    getDifferentNodes( child, fileTrace, areSnapshotsReversed );
                }
            } else {
                if( areSnapshotsReversed ) {
                    fileTrace.addDifference( "Presence of XML node " + child.getFullSignature( "" ),
                                             "\n\t" + "NO", "YES" );
                } else {
                    fileTrace.addDifference( "Presence of XML node " + child.getFullSignature( "" ),
                                             "\n\t" + "YES", "NO" );
                }
            }
        }
    }

    /**
     * Makes XML Document from text
     *  
     * @param xmlFileContent
     * @return
     * @throws XMLException
     */
    public Document loadXmlDocument( String xmlFileContent ) throws XMLException {

        try {
            return new SAXReader().read( new StringReader( xmlFileContent ) );
        } catch( XMLException | DocumentException e ) {
            throw new XMLException( "Error parsing XML file: " + xmlFileContent, e );
        }
    }

    private XmlNode loadXmlFile( String agent, String filePath ) {

        String xmlFileContent;
        if( agent == null ) {
            // It is a local file
            try {
                xmlFileContent = new LocalFileSystemOperations().readFile( filePath, "UTF-8" );
            } catch( Exception e ) {
                // this will cancel the comparison
                // the other option is to add a difference to the FileTrace object, instead of throwing an exception here
                throw new FileSystemOperationException( "Error loading '" + filePath + "' XML file." );
            }
        } else {
            // It is a remote file.
            // As we need to use Action Library code in order to get the file content, here we use
            // java reflection, so do not need to introduce compile dependency
            try {
                Class<?> fileSystemOperationsClass = Class.forName( "com.tumbleweed.automation.actions.filesystem.RemoteFileSystemOperations" );
                Object fileSystemOperationsInstance = fileSystemOperationsClass.getConstructor( String.class )
                                                                               .newInstance( agent );

                //call the printIt method
                Method readFileMethod = fileSystemOperationsClass.getDeclaredMethod( "readFile", String.class,
                                                                                     String.class );
                xmlFileContent = readFileMethod.invoke( fileSystemOperationsInstance, filePath, "UTF-8" )
                                               .toString();
            } catch( Exception e ) {
                // this will cancel the comparison
                // the other option is to add a difference to the FileTrace object, instead of throwing an exception here
                throw new FileSystemOperationException( "Error loading '" + filePath + "' XML file from "
                                                        + agent );
            }
        }

        Document xmlDocument = loadXmlDocument( xmlFileContent );

        return new XmlNode( null, xmlDocument.getRootElement() );
    }
}
