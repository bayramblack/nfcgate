package work.undernet.nfc.nfc.chip.detectors;

import java.util.List;

import work.undernet.nfc.nfc.chip.NfcChipGuess;

/**
 * Common interface for all NFCC detectors
 */
public interface INfcChipDetector {
    List<NfcChipGuess> tryDetect();
}
