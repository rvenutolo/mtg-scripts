#!/usr/bin/env groovy

@Grab(group = 'org.apache.commons', module = 'commons-csv', version = '1.6')

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.apache.commons.csv.CSVRecord

static void printNotImportedCards(final String message, final List<CardCount> cards) {
    System.err.println('-' * 80)
    System.err.println(message)
    System.err.println('')
    cards.each {
        System.err.println("${it.name}${it.isFoil ? ' (FOIL)' : ''} - ${it.setCode} - ${it.count}")
    }
    System.err.println('')
}

if (args && args.size() != 1) {
    throw new IllegalArgumentException("Expected at most one argument: ${args}")
}


// Read MTGGoldfish collection to import to EchoMTG

final InputStream inputStream = args ? new File(args[0]).newInputStream() : System.in

final CardCollection goldfishCollection = new CardCollection()

inputStream.withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(reader).each { final CSVRecord csvRecord ->
        final Card card = new Card(
            name: csvRecord.get('Card'),
            set: new CardSet(
                setName: csvRecord.get('Set Name'),
                setCode: csvRecord.get('Set ID')
            ),
            isFoil: csvRecord.get('Foil') == 'FOIL',
            language: 'EN' // MTGGoldfish does not track language, so default to English
        )
        final count = csvRecord.get('Quantity') as int
        goldfishCollection.addCards(card, count)
    }
}


// MTGGoldfish and EchoMTG do not use all the same set names and codes
// Read data to convert MTGGoldfish set names and codes to those used by EchoMTG

final Map<CardSet, CardSet> goldfishToEchoSets = [:]

new File('goldfish_to_echo_sets.csv').withReader('UTF-8') { final Reader reader ->
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

// Create collection to import to EchoMTG, reading from MTGGoldfish and converting sets as necessary

final CardCollection echoCollection = new CardCollection()

goldfishCollection.cardCounts.each {
    final Card card = new Card(
        name: it.name,
        set: goldfishToEchoSets[it.set] ?: it.set,
        isFoil: it.isFoil,
        language: it.language
    )
    echoCollection.addCards(card, it.count)
}


// Read the EchoMTG set data to use in validation of set names and codes

final Map<String, String> echoSets = [:]

new File('echo_sets.csv').withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withHeader('Name', 'Code').parse(reader).each { final CSVRecord csvRecord ->
        echoSets[csvRecord.get('Code')] = csvRecord.get('Name')
    }
}


// Add some cards to EchoMTG collection, such as non-English cards and Beta basics

new File('add_to_echo_import.csv').withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withHeader(
        'Name', 'Set Code', 'Count', 'Language'
    ).parse(reader).each { final CSVRecord csvRecord ->
        final Card card = new Card(
            name: csvRecord.get('Name'),
            set: new CardSet(
                setName: echoSets[csvRecord.get('Set Code')],
                setCode: csvRecord.get('Set Code')
            ),
            isFoil: false, // currently don't have any foils to add
            language: csvRecord.get('Language') ?: 'EN' // if not specified, use English
        )
        final int count = csvRecord.get('Count') as int
        echoCollection.addCards(card, count)
    }
}

// Go through collection to find set names and codes not in EchoMTG and fail if there are any

final List<CardSet> badCardSets = echoCollection.cardSets.findAll {
    !(it.setCode in echoSets.keySet()) || (it.setName != echoSets[it.setCode])
}
if (badCardSets) {
    throw new IllegalArgumentException("Card sets not in EchoMTG set data:\n${badCardSets.join('\n')}")
}


// There are some cards I do not want to import from MTGGoldfish to EchoMTG
// Read and remove those cards

final List<CardCount> skippedCards = []

new File('skip_in_echo_import.csv').withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withHeader('Name', 'Set', 'Count').parse(reader).each { final CSVRecord csvRecord ->
        final Card card = new Card(
            name: csvRecord.get('Name'),
            set: new CardSet(
                setName: echoSets[csvRecord.get('Set')],
                setCode: csvRecord.get('Set')
            ),
            isFoil: false, // currently don't have any foils to skip
            language: 'EN' // currently dont' have any non-English to skip
        )
        final int count = csvRecord.get('Count') as int
        echoCollection.removeCards(card, count)
        skippedCards << new CardCount(card, count)
    }
}


// MTGGoldfish CSV does not distinctly identify different artworks for basic lands,
// and they're generally not of much value, so just remove all non-Unstable basics
// Basic lands I care to import (ex Beta basics) are added from file elsewhere

echoCollection.cardCounts.findAll {
    it.name in ['Forest', 'Island', 'Mountain', 'Plains', 'Swamp', 'Wastes'] && it.setCode != 'UN3'
}.each {
    echoCollection.removeCards(it.card, it.count)
}



// There are a number of cards that have multiple artworks in the same set
// Ex: Hymn to Tourach and High Tide in Fallen Empires
// The MTGGoldfish CSV does not distinctly identify these
// Collect these for later output and remove them from EchoMTG collection

final Map<String, List<String>> multipleArtworks = [:].withDefault { [] }

new File('cards_with_multiple_artworks.csv').withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withHeader('Set', 'Name').parse(reader).each { final CSVRecord csvRecord ->
        multipleArtworks[csvRecord.get('Set').trim()] << csvRecord.get('Name').trim()
    }
}

final List<CardCount> cardsWithMultipleArtworks = echoCollection.cardCounts.findAll {
    multipleArtworks[it.setCode].contains(it.name)
}
cardsWithMultipleArtworks.each {
    echoCollection.removeCards(it.card, it.count)
}


// EchoMTG CSV import does not handle split cards
// Collect these cards for output later and remove them from EchoMTG collection

final List<CardCount> splitCards = echoCollection.cardCounts.findAll {
    it.name.contains('/')
}
splitCards.each {
    echoCollection.removeCards(it.card, it.count)
}


// Output cards to import to EchoMTG to stdout

CSVFormat.DEFAULT.printer().withCloseable { final CSVPrinter csvPrinter ->
    csvPrinter.printRecord('Reg Qty', 'Foil Qty', 'Name', 'Set', 'Acquired', 'Language')
    echoCollection.cardCounts.each {
        csvPrinter.printRecord(
            it.isFoil ? 0 : it.count,
            it.isFoil ? it.count : 0,
            it.name,
            // Echo will import successfully if using set code, but it may import the wrong set, so use full set name
            // ex: 'Dark Ritual,CST' will import as the A25 version,
            //     but 'Dark Ritual,MMQ' will import as the correct version
            it.setName,
            '', // acquired price field
            it.language
        )
    }
}


// Output cards I need to handle manually to stderr

printNotImportedCards('Cards with multiple artworks in set', cardsWithMultipleArtworks)
printNotImportedCards('Split cards', splitCards)


// Output skipped cards to stderr

printNotImportedCards('Cards skipped', skippedCards)
