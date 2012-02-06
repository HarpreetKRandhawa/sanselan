/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.sanselan.formats.tiff;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.sanselan.FormatCompliance;
import org.apache.commons.sanselan.ImageFormat;
import org.apache.commons.sanselan.ImageInfo;
import org.apache.commons.sanselan.ImageParser;
import org.apache.commons.sanselan.ImageReadException;
import org.apache.commons.sanselan.ImageWriteException;
import org.apache.commons.sanselan.common.IImageMetadata;
import org.apache.commons.sanselan.common.bytesource.ByteSource;
import org.apache.commons.sanselan.formats.tiff.TiffDirectory.ImageDataElement;
import org.apache.commons.sanselan.formats.tiff.constants.TiffConstants;
import org.apache.commons.sanselan.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.sanselan.formats.tiff.datareaders.DataReader;
import org.apache.commons.sanselan.formats.tiff.photometricinterpreters.PhotometricInterpreter;
import org.apache.commons.sanselan.formats.tiff.photometricinterpreters.PhotometricInterpreterBiLevel;
import org.apache.commons.sanselan.formats.tiff.photometricinterpreters.PhotometricInterpreterCieLab;
import org.apache.commons.sanselan.formats.tiff.photometricinterpreters.PhotometricInterpreterCmyk;
import org.apache.commons.sanselan.formats.tiff.photometricinterpreters.PhotometricInterpreterLogLuv;
import org.apache.commons.sanselan.formats.tiff.photometricinterpreters.PhotometricInterpreterPalette;
import org.apache.commons.sanselan.formats.tiff.photometricinterpreters.PhotometricInterpreterRgb;
import org.apache.commons.sanselan.formats.tiff.photometricinterpreters.PhotometricInterpreterYCbCr;
import org.apache.commons.sanselan.formats.tiff.write.TiffImageWriterLossy;

public class TiffImageParser extends ImageParser implements TiffConstants
{
    public TiffImageParser()
    {
        // setDebug(true);
    }

    public String getName()
    {
        return "Tiff-Custom";
    }

    public String getDefaultExtension()
    {
        return DEFAULT_EXTENSION;
    }

    private static final String DEFAULT_EXTENSION = ".tif";

    private static final String ACCEPTED_EXTENSIONS[] = { ".tif", ".tiff", };

    protected String[] getAcceptedExtensions()
    {
        return ACCEPTED_EXTENSIONS;
    }

    protected ImageFormat[] getAcceptedTypes()
    {
        return new ImageFormat[] { ImageFormat.IMAGE_FORMAT_TIFF, //
        };
    }

    public byte[] getICCProfileBytes(ByteSource byteSource, Map params)
            throws ImageReadException, IOException
    {
        FormatCompliance formatCompliance = FormatCompliance.getDefault();
        TiffContents contents = new TiffReader(isStrict(params))
                .readFirstDirectory(byteSource, params, false, formatCompliance);
        TiffDirectory directory = contents.directories.get(0);

        TiffField field = directory.findField(EXIF_TAG_ICC_PROFILE);
        if (null == field)
            return null;
        return field.oversizeValue;
    }

    public Dimension getImageSize(ByteSource byteSource, Map params)
            throws ImageReadException, IOException
    {
        FormatCompliance formatCompliance = FormatCompliance.getDefault();
        TiffContents contents = new TiffReader(isStrict(params))
                .readFirstDirectory(byteSource, params, false, formatCompliance);
        TiffDirectory directory = contents.directories.get(0);

        int width = directory.findField(TiffTagConstants.IMAGE_WIDTH.tagInfo).getIntValue();
        int height = directory.findField(TiffTagConstants.IMAGE_LENGTH.tagInfo).getIntValue();

        return new Dimension(width, height);
    }

    public byte[] embedICCProfile(byte image[], byte profile[])
    {
        return null;
    }

    public boolean embedICCProfile(File src, File dst, byte profile[])
    {
        return false;
    }

    public IImageMetadata getMetadata(ByteSource byteSource, Map params)
            throws ImageReadException, IOException
    {
        FormatCompliance formatCompliance = FormatCompliance.getDefault();
        TiffContents contents = new TiffReader(isStrict(params)).readContents(
                byteSource, params, formatCompliance);

        List<TiffDirectory> directories = contents.directories;

        TiffImageMetadata result = new TiffImageMetadata(contents);

        for (int i = 0; i < directories.size(); i++)
        {
            TiffDirectory dir = directories.get(i);

            TiffImageMetadata.Directory metadataDirectory = new TiffImageMetadata.Directory(dir);

            List<TiffField> entries = dir.getDirectoryEntrys();

            for (int j = 0; j < entries.size(); j++)
            {
                TiffField entry = entries.get(j);
                metadataDirectory.add(entry);
            }

            result.add(metadataDirectory);
        }

        return result;
    }

    public ImageInfo getImageInfo(ByteSource byteSource, Map params)
            throws ImageReadException, IOException
    {
        FormatCompliance formatCompliance = FormatCompliance.getDefault();
        TiffContents contents = new TiffReader(isStrict(params))
                .readDirectories(byteSource, false, formatCompliance);
        TiffDirectory directory = contents.directories.get(0);

        TiffField widthField = directory.findField(TiffTagConstants.IMAGE_WIDTH.tagInfo, true);
        TiffField heightField = directory
                .findField(TiffTagConstants.IMAGE_LENGTH.tagInfo, true);

        if ((widthField == null) || (heightField == null))
            throw new ImageReadException("TIFF image missing size info.");

        int height = heightField.getIntValue();
        int width = widthField.getIntValue();

        // -------------------

        TiffField resolutionUnitField = directory
                .findField(TiffTagConstants.RESOLUTION_UNIT.tagInfo);
        int resolutionUnit = 2; // Inch
        if ((resolutionUnitField != null)
                && (resolutionUnitField.getValue() != null))
            resolutionUnit = resolutionUnitField.getIntValue();

        double unitsPerInch = -1;
        switch (resolutionUnit)
        {
        case 1:
            break;
        case 2: // Inch
            unitsPerInch = 1.0;
            break;
        case 3: // Meter
            unitsPerInch = 0.0254;
            break;
        default:
            break;

        }
        TiffField xResolutionField = directory.findField(TiffTagConstants.XRESOLUTION.tagInfo);
        TiffField yResolutionField = directory.findField(TiffTagConstants.YRESOLUTION.tagInfo);

        int physicalWidthDpi = -1;
        float physicalWidthInch = -1;
        int physicalHeightDpi = -1;
        float physicalHeightInch = -1;

        if (unitsPerInch > 0)
        {
            if ((xResolutionField != null)
                    && (xResolutionField.getValue() != null))
            {
                double XResolutionPixelsPerUnit = xResolutionField
                        .getDoubleValue();
                physicalWidthDpi = (int) (XResolutionPixelsPerUnit / unitsPerInch);
                physicalWidthInch = (float) (width / (XResolutionPixelsPerUnit * unitsPerInch));
            }
            if ((yResolutionField != null)
                    && (yResolutionField.getValue() != null))
            {
                double YResolutionPixelsPerUnit = yResolutionField
                        .getDoubleValue();
                physicalHeightDpi = (int) (YResolutionPixelsPerUnit / unitsPerInch);
                physicalHeightInch = (float) (height / (YResolutionPixelsPerUnit * unitsPerInch));
            }
        }

        // -------------------

        TiffField bitsPerSampleField = directory
                .findField(TiffTagConstants.BITS_PER_SAMPLE.tagInfo);

        int bitsPerSample = 1;
        if ((bitsPerSampleField != null)
                && (bitsPerSampleField.getValue() != null))
            bitsPerSample = bitsPerSampleField.getIntValueOrArraySum();

        int bitsPerPixel = bitsPerSample; // assume grayscale;
        // dunno if this handles colormapped images correctly.

        // -------------------

        List<String> comments = new ArrayList<String>();
        List<TiffField> entries = directory.entries;
        for (int i = 0; i < entries.size(); i++)
        {
            TiffField field = entries.get(i);
            String comment = field.toString();
            comments.add(comment);
        }

        ImageFormat format = ImageFormat.IMAGE_FORMAT_TIFF;
        String formatName = "TIFF Tag-based Image File Format";
        String mimeType = "image/tiff";
        int numberOfImages = contents.directories.size();
        // not accurate ... only reflects first
        boolean isProgressive = false;
        // is TIFF ever interlaced/progressive?

        String formatDetails = "Tiff v." + contents.header.tiffVersion;

        boolean isTransparent = false; // TODO: wrong
        boolean usesPalette = false;
        TiffField colorMapField = directory.findField(TiffTagConstants.COLOR_MAP.tagInfo);
        if (colorMapField != null)
            usesPalette = true;

        int colorType = ImageInfo.COLOR_TYPE_RGB;

        int compression = directory.findField(TiffTagConstants.COMPRESSION.tagInfo)
                .getIntValue();
        String compressionAlgorithm;

        switch (compression)
        {
        case TIFF_COMPRESSION_UNCOMPRESSED_1:
            compressionAlgorithm = ImageInfo.COMPRESSION_ALGORITHM_NONE;
            break;
        case TIFF_COMPRESSION_CCITT_1D:
            compressionAlgorithm = ImageInfo.COMPRESSION_ALGORITHM_CCITT_1D;
            break;
        case TIFF_COMPRESSION_CCITT_GROUP_3:
            compressionAlgorithm = ImageInfo.COMPRESSION_ALGORITHM_CCITT_GROUP_3;
            break;
        case TIFF_COMPRESSION_CCITT_GROUP_4:
            compressionAlgorithm = ImageInfo.COMPRESSION_ALGORITHM_CCITT_GROUP_4;
            break;
        case TIFF_COMPRESSION_LZW:
            compressionAlgorithm = ImageInfo.COMPRESSION_ALGORITHM_LZW;
            break;
        case TIFF_COMPRESSION_JPEG:
            compressionAlgorithm = ImageInfo.COMPRESSION_ALGORITHM_JPEG;
            break;
        case TIFF_COMPRESSION_UNCOMPRESSED_2:
            compressionAlgorithm = ImageInfo.COMPRESSION_ALGORITHM_NONE;
            break;
        case TIFF_COMPRESSION_PACKBITS:
            compressionAlgorithm = ImageInfo.COMPRESSION_ALGORITHM_PACKBITS;
            break;
        default:
            compressionAlgorithm = ImageInfo.COMPRESSION_ALGORITHM_UNKNOWN;
            break;
        }

        ImageInfo result = new ImageInfo(formatDetails, bitsPerPixel, comments,
                format, formatName, height, mimeType, numberOfImages,
                physicalHeightDpi, physicalHeightInch, physicalWidthDpi,
                physicalWidthInch, width, isProgressive, isTransparent,
                usesPalette, colorType, compressionAlgorithm);

        return result;
    }

    public String getXmpXml(ByteSource byteSource, Map params)
            throws ImageReadException, IOException
    {
        FormatCompliance formatCompliance = FormatCompliance.getDefault();
        TiffContents contents = new TiffReader(isStrict(params))
                .readDirectories(byteSource, false, formatCompliance);
        TiffDirectory directory = contents.directories.get(0);

        TiffField xmpField = directory.findField(TiffTagConstants.XMP.tagInfo, false);
        if (xmpField == null)
            return null;

        byte bytes[] = xmpField.getByteArrayValue();

        try
        {
            // segment data is UTF-8 encoded xml.
            String xml = new String(bytes, "utf-8");
            return xml;
        } catch (UnsupportedEncodingException e)
        {
            throw new ImageReadException("Invalid JPEG XMP Segment.");
        }
    }

    public boolean dumpImageFile(PrintWriter pw, ByteSource byteSource)
            throws ImageReadException, IOException
    {
        try
        {
            pw.println("tiff.dumpImageFile");

            {
                ImageInfo imageData = getImageInfo(byteSource);
                if (imageData == null)
                    return false;

                imageData.toString(pw, "");
            }

            pw.println("");

            // try
            {
                FormatCompliance formatCompliance = FormatCompliance
                        .getDefault();
                Map params = null;
                TiffContents contents = new TiffReader(true).readContents(
                        byteSource, params, formatCompliance);

                List<TiffDirectory> directories = contents.directories;

                if (directories == null)
                    return false;

                for (int d = 0; d < directories.size(); d++)
                {
                    TiffDirectory directory = directories
                            .get(d);

                    List<TiffField> entries = directory.entries;

                    if (entries == null)
                        return false;

                    // Debug.debug("directory offset", directory.offset);

                    for (int i = 0; i < entries.size(); i++)
                    {
                        TiffField field = entries.get(i);

                        field.dump(pw, d + "");
                    }
                }

                pw.println("");
            }
            // catch (Exception e)
            // {
            // Debug.debug(e);
            // pw.println("");
            // return false;
            // }

            return true;
        } finally
        {
            pw.println("");
        }
    }

    public FormatCompliance getFormatCompliance(ByteSource byteSource)
            throws ImageReadException, IOException
    {
        FormatCompliance formatCompliance = FormatCompliance.getDefault();
        Map params = null;
        new TiffReader(isStrict(params)).readContents(byteSource, params,
                formatCompliance);
        return formatCompliance;
    }

    public List<byte[]> collectRawImageData(ByteSource byteSource, Map params)
            throws ImageReadException, IOException
    {
        FormatCompliance formatCompliance = FormatCompliance.getDefault();
        TiffContents contents = new TiffReader(isStrict(params))
                .readDirectories(byteSource, true, formatCompliance);

        List<byte[]> result = new ArrayList<byte[]>();
        for (int i = 0; i < contents.directories.size(); i++)
        {
            TiffDirectory directory = contents.directories
                    .get(i);
            List<ImageDataElement> dataElements = directory.getTiffRawImageDataElements();
            for (int j = 0; j < dataElements.size(); j++)
            {
                TiffDirectory.ImageDataElement element = (TiffDirectory.ImageDataElement) dataElements
                        .get(j);
                byte bytes[] = byteSource.getBlock(element.offset,
                        element.length);
                result.add(bytes);
            }
        }
        return result;
    }

    public BufferedImage getBufferedImage(ByteSource byteSource, Map params)
            throws ImageReadException, IOException
    {
        FormatCompliance formatCompliance = FormatCompliance.getDefault();
        TiffContents contents = new TiffReader(isStrict(params))
                .readFirstDirectory(byteSource, params, true, formatCompliance);
        TiffDirectory directory = contents.directories.get(0);
        BufferedImage result = directory.getTiffImage(params);
        if (null == result)
            throw new ImageReadException("TIFF does not contain an image.");
        return result;
    }

    public List<BufferedImage> getAllBufferedImages(ByteSource byteSource)
            throws ImageReadException, IOException
    {
        FormatCompliance formatCompliance = FormatCompliance.getDefault();
        TiffContents contents = new TiffReader(true).readDirectories(byteSource, true, formatCompliance);
        List<BufferedImage> results = new ArrayList<BufferedImage>();
        for (int i = 0; i < contents.directories.size(); i++)
        {
            TiffDirectory directory = contents.directories.get(i);
            BufferedImage result = directory.getTiffImage(null);
            if (result != null)
            {
                results.add(result);
            }
        }
        return results;
    } 

    protected BufferedImage getBufferedImage(TiffDirectory directory, Map params)
            throws ImageReadException, IOException
    {
        List<TiffField> entries = directory.entries;

        if (entries == null)
            throw new ImageReadException("TIFF missing entries");

        int photometricInterpretation = directory.findField(
                TiffTagConstants.PHOTOMETRIC_INTERPRETATION.tagInfo, true).getIntValue();
        int compression = directory.findField(TiffTagConstants.COMPRESSION.tagInfo, true)
                .getIntValue();
        int width = directory.findField(TiffTagConstants.IMAGE_WIDTH.tagInfo, true)
                .getIntValue();
        int height = directory.findField(TiffTagConstants.IMAGE_LENGTH.tagInfo, true)
                .getIntValue();
        int samplesPerPixel = 1;
        TiffField samplesPerPixelField = directory.findField(TiffTagConstants.SAMPLES_PER_PIXEL.tagInfo);
        if (samplesPerPixelField != null)
            samplesPerPixel = samplesPerPixelField.getIntValue();
        int bitsPerSample[] = { 1 };
        int bitsPerPixel = samplesPerPixel;
        TiffField bitsPerSampleField = directory.findField(TiffTagConstants.BITS_PER_SAMPLE.tagInfo);
        if (bitsPerSampleField != null)
        {
            bitsPerSample = bitsPerSampleField.getIntArrayValue();
            bitsPerPixel = bitsPerSampleField.getIntValueOrArraySum();
        }

        // int bitsPerPixel = getTagAsValueOrArraySum(entries,
        // TIFF_TAG_BITS_PER_SAMPLE);

        int predictor = -1;
        {
            // dumpOptionalNumberTag(entries, TIFF_TAG_FILL_ORDER);
            // dumpOptionalNumberTag(entries, TIFF_TAG_FREE_BYTE_COUNTS);
            // dumpOptionalNumberTag(entries, TIFF_TAG_FREE_OFFSETS);
            // dumpOptionalNumberTag(entries, TIFF_TAG_ORIENTATION);
            // dumpOptionalNumberTag(entries, TIFF_TAG_PLANAR_CONFIGURATION);
            TiffField predictorField = directory.findField(TiffTagConstants.PREDICTOR.tagInfo);
            if (null != predictorField)
                predictor = predictorField.getIntValueOrArraySum();
        }

        if (samplesPerPixel != bitsPerSample.length)
            throw new ImageReadException("Tiff: samplesPerPixel ("
                    + samplesPerPixel + ")!=fBitsPerSample.length ("
                    + bitsPerSample.length + ")");

        boolean hasAlpha = false;
        BufferedImage result = getBufferedImageFactory(params)
                .getColorBufferedImage(width, height, hasAlpha);

        PhotometricInterpreter photometricInterpreter = getPhotometricInterpreter(
                directory, photometricInterpretation, bitsPerPixel,
                bitsPerSample, predictor, samplesPerPixel, width, height);

        TiffImageData imageData = directory.getTiffImageData();

        DataReader dataReader = imageData.getDataReader(directory,
                photometricInterpreter, bitsPerPixel, bitsPerSample, predictor,
                samplesPerPixel, width, height, compression);

        dataReader.readImageData(result);

        photometricInterpreter.dumpstats();

        return result;
    }

    private PhotometricInterpreter getPhotometricInterpreter(
            TiffDirectory directory, int photometricInterpretation,
            int bitsPerPixel, int bitsPerSample[], int predictor,
            int samplesPerPixel, int width, int height) throws ImageReadException
    {
        switch (photometricInterpretation)
        {
        case 0:
        case 1:
            boolean invert = photometricInterpretation == 0;

            return new PhotometricInterpreterBiLevel(bitsPerPixel,
                    samplesPerPixel, bitsPerSample, predictor, width, height,
                    invert);
        case 3: // Palette
        {
            int colorMap[] = directory.findField(TiffTagConstants.COLOR_MAP.tagInfo, true)
                    .getIntArrayValue();

            int expected_colormap_size = 3 * (1 << bitsPerPixel);

            if (colorMap.length != expected_colormap_size)
                throw new ImageReadException("Tiff: fColorMap.length ("
                        + colorMap.length + ")!=expected_colormap_size ("
                        + expected_colormap_size + ")");

            return new PhotometricInterpreterPalette(samplesPerPixel,
                    bitsPerSample, predictor, width, height, colorMap);
        }
        case 2: // RGB
            return new PhotometricInterpreterRgb(samplesPerPixel,
                    bitsPerSample, predictor, width, height);
        case 5: // CMYK
            return new PhotometricInterpreterCmyk(samplesPerPixel,
                    bitsPerSample, predictor, width, height);
        case 6: //
        {
            double yCbCrCoefficients[] = directory.findField(
                    TiffTagConstants.YCBCR_COEFFICIENTS.tagInfo, true).getDoubleArrayValue();

            int yCbCrPositioning[] = directory.findField(
                    TiffTagConstants.YCBCR_POSITIONING.tagInfo, true).getIntArrayValue();
            int yCbCrSubSampling[] = directory.findField(
                    TiffTagConstants.YCBCR_SUB_SAMPLING.tagInfo, true).getIntArrayValue();

            double referenceBlackWhite[] = directory.findField(
                    TiffTagConstants.REFERENCE_BLACK_WHITE.tagInfo, true).getDoubleArrayValue();

            return new PhotometricInterpreterYCbCr(yCbCrCoefficients,
                    yCbCrPositioning, yCbCrSubSampling, referenceBlackWhite,
                    samplesPerPixel, bitsPerSample, predictor, width, height);
        }

        case 8:
            return new PhotometricInterpreterCieLab(samplesPerPixel,
                    bitsPerSample, predictor, width, height);

        case 32844:
        case 32845: {
            boolean yonly = (photometricInterpretation == 32844);
            return new PhotometricInterpreterLogLuv(samplesPerPixel,
                    bitsPerSample, predictor, width, height, yonly);
        }

        default:
            throw new ImageReadException(
                    "TIFF: Unknown fPhotometricInterpretation: "
                            + photometricInterpretation);
        }
    }

    public void writeImage(BufferedImage src, OutputStream os, Map params)
            throws ImageWriteException, IOException
    {
        new TiffImageWriterLossy().writeImage(src, os, params);
    }

}
