@Grab(group = 'org.apache.commons', module = 'commons-csv', version = '1.6')

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.apache.commons.csv.CSVRecord

if (args && args.size() != 1) {
    throw new IllegalArgumentException("Expected at most one argument: ${args}")
}

final File rootDir = new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile
final File csvDir = new File(rootDir, 'csv_files')

final File goldfishToEchoSetsFile = new File(csvDir, 'goldfish_to_echo_sets.csv')
final File echoSetsFile = new File(csvDir, 'echo_sets.csv')
final File addToEchoImportFile = new File(csvDir, 'add_to_echo_import.csv')
final File skipInImportFile = new File(csvDir, 'skip_in_echo_import.csv')
final File multipleArtworksFile = new File(csvDir, 'cards_with_multiple_artworks.csv')

// MTGGoldfish and EchoMTG do not use all the same set names and codes
// Read data to convert MTGGoldfish set names and codes to those used by EchoMTG

final Map<CardSet, CardSet> goldfishToEchoSets = [:]

goldfishToEchoSetsFile.withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withHeader(
        'Goldfish Name', 'Goldfish Code', 'Echo Name', 'Echo Code'
    ).parse(reader).each { final CSVRecord csvRecord ->
        final CardSet goldfishSet = new CardSet(
            setName: csvRecord.get('Goldfish Name'),
            setCode: csvRecord.get('Goldfish Code')
        )
        final CardSet echoSet = new CardSet(
            setName: csvRecord.get('Echo Name'),
            setCode: csvRecord.get('Echo Code')
        )
        goldfishToEchoSets[goldfishSet] = echoSet
    }
}

// Read MTGGoldfish collection to import to EchoMTG,
// converting sets as necessary

final InputStream inputStream = args ? new File(args[0]).newInputStream() : System.in

final CardCollection cardCollection = new CardCollection()

inputStream.withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(reader).each { final CSVRecord csvRecord ->
        final CardSet goldfishSet = new CardSet(
            setName: csvRecord.get('Set Name'),
            setCode: csvRecord.get('Set ID')
        )
        final Card card = new Card(
            name: csvRecord.get('Card'),
            set: goldfishToEchoSets[goldfishSet] ?: goldfishSet,
            isFoil: csvRecord.get('Foil') == 'FOIL',
            language: 'EN' // MTGGoldfish does not track language, so default to English
        )
        final count = csvRecord.get('Quantity') as int
        if (count > 0) {
            // Goldfish data will have 0 quantity cards
            cardCollection.add(card, count)
        }
    }
}

// Read the EchoMTG set data to use in validation of set names and codes

final Set<CardSet> echoSets = []
final Map<String, String> echoSetCodeToName = [:].withDefault { '' }

echoSetsFile.withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withHeader('Name', 'Code').parse(reader).each { final CSVRecord csvRecord ->
        final CardSet cardSet = new CardSet(
            setName: csvRecord.get('Name'),
            setCode: csvRecord.get('Code')
        )
        echoSets << cardSet
        echoSetCodeToName[cardSet.setCode] = cardSet.setName
    }
}


// Add some cards to collection, such as non-English cards and Beta basics

addToEchoImportFile.withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withHeader(
        'Name', 'Set Code', 'Count', 'Language'
    ).parse(reader).each { final CSVRecord csvRecord ->
        final CardSet cardSet = new CardSet(
            setCode: csvRecord.get('Set Code'),
            setName: echoSetCodeToName[csvRecord.get('Set Code')]
        )
        final Card card = new Card(
            name: csvRecord.get('Name'),
            set: cardSet,
            isFoil: false, // currently don't have any foils to add
            language: csvRecord.get('Language') ?: 'EN' // if not specified, use English
        )
        final int count = csvRecord.get('Count') as int
        cardCollection.add(card, count)
    }
}

// Go through collection to find set names and codes not in EchoMTG and fail if there are any

final List<CardSet> badCardSets = cardCollection.cardSets - echoSets
if (badCardSets) {
    throw new IllegalArgumentException("Card sets not in master EchoMTG set data:\n${badCardSets.sort().join('\n')}")
}

// There are some cards I do not want to import from MTGGoldfish to EchoMTG
// Read and remove those cards

final List<CardCount> skippedCards = []

skipInImportFile.withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withHeader('Name', 'Set Code', 'Count').parse(reader).each { final CSVRecord csvRecord ->
        final CardSet cardSet = new CardSet(
            setCode: csvRecord.get('Set Code'),
            setName: echoSetCodeToName[csvRecord.get('Set Code')]
        )
        final Card card = new Card(
            name: csvRecord.get('Name'),
            set: cardSet,
            isFoil: false, // currently don't have any foils to skip
            language: 'EN' // currently dont' have any non-English to skip
        )
        final int count = csvRecord.get('Count') as int
        final CardCount cardCount = new CardCount(
            card: card,
            count: count
        )
        skippedCards << cardCount
    }
}

skippedCards.each { final CardCount cardCount ->
    cardCollection.remove(cardCount.card, cardCount.count)
}

// MTGGoldfish CSV does not distinctly identify different artworks for basic lands,
// and they're generally not of much value, so just remove all non-Unstable basics
// Basic lands I care to import (ex Beta basics) are added from file elsewhere

cardCollection.removeAll { final CardCount cardCount ->
    cardCount.name in ['Forest', 'Island', 'Mountain', 'Plains', 'Swamp', 'Wastes'] && cardCount.setCode != 'UN3'
}

// There are a number of cards that have multiple artworks in the same set
// Ex: Hymn to Tourach and High Tide in Fallen Empires
// The MTGGoldfish CSV does not distinctly identify these
// Collect these for later output and remove them

final Map<String, List<String>> multipleArtworks = [:].withDefault { [] }

multipleArtworksFile.withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withHeader('Set', 'Name').parse(reader).each { final CSVRecord csvRecord ->
        multipleArtworks[csvRecord.get('Set').trim()] << csvRecord.get('Name').trim()
    }
}

final List<CardCount> cardsWithMultipleArtworks = cardCollection.removeAll { final CardCount cardCount ->
    multipleArtworks[cardCount.setCode].contains(cardCount.name)
}

// EchoMTG CSV import does not handle split cards
// Collect these cards for output later and remove them

final List<CardCount> splitCards = cardCollection.removeAll { final CardCount cardCount ->
    cardCount.name.contains('/')
}

// Output cards to import to EchoMTG to stdout

CSVFormat.DEFAULT.printer().withCloseable { final CSVPrinter csvPrinter ->
    csvPrinter.printRecord('Reg Qty', 'Foil Qty', 'Name', 'Set', 'Acquired', 'Language')
    cardCollection.cardCounts.sort().each { final CardCount cardCount ->
        csvPrinter.printRecord(
            cardCount.isFoil ? 0 : cardCount.count,
            cardCount.isFoil ? cardCount.count : 0,
            cardCount.name,
            // Echo will import successfully if using set code, but it may import the wrong set, so use full set name
            // ex: 'Dark Ritual,CST' will import as the A25 version,
            //     but 'Dark Ritual,MMQ' will import as the correct version
            cardCount.setName,
            '', // acquired price field
            cardCount.language
        )
    }
}

// Closure to print to stderr

final Closure printNotImportedCards = { final String message, final List<CardCount> cards ->
    System.err.println('-' * 80)
    System.err.println(message)
    System.err.println('')
    cards.sort().each { final CardCount cardCount ->
        System.err.println(cardCount)
    }
    System.err.println('')
}

// Output cards I need to handle manually to stderr

printNotImportedCards('Cards with multiple artworks in set', cardsWithMultipleArtworks)
printNotImportedCards('Split cards', splitCards)

// Output skipped cards to stderr

printNotImportedCards('Cards skipped', skippedCards)
