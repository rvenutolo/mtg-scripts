#!/usr/bin/env groovy

@Grab(group = 'org.apache.commons', module = 'commons-csv', version = '1.6')

import groovy.transform.Canonical
import groovy.transform.Sortable
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.apache.commons.csv.CSVRecord

@Canonical
@Sortable
class Entry {
    String name
    String setName
    String setCode
    int count
    boolean isFoil
    String language
}

static void printNotImportedEntries(final String message, final List<Entry> entries) {
    System.err.println('-' * 80)
    System.err.println(message)
    System.err.println('')
    entries.sort().each {
        System.err.println("${it.name}${it.isFoil ? ' (FOIL)' : ''} - ${it.setCode} - ${it.count}")
    }
    System.err.println('')
}

if (args && args.size() != 1) {
    throw new IllegalArgumentException("Expected at most one argument: ${args}")
}


// Read MTGGoldfish entries to import to EchoMTG

final InputStream inputStream = args ? new File(args[0]).newInputStream() : System.in

final List<Entry> entries = []

inputStream.withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(reader).each { final CSVRecord csvRecord ->
        entries << new Entry(
            name: csvRecord.get('Card'),
            setName: csvRecord.get('Set Name'),
            setCode: csvRecord.get('Set ID'),
            count: csvRecord.get('Quantity') as int,
            isFoil: csvRecord.get('Foil') == 'FOIL',
            language: 'EN' // MTGGoldfish does not track language, so default to English
        )
    }
}


// MTGGoldfish and EchoMTG do not use all the same set names and codes
// Read data to convert MTGGoldfish set names and codes to those used by EchoMTG

final Map<Tuple2<String, String>, Tuple2<String, String>> goldfishToEchoSets = [:]

new File('goldfish_to_echo_sets.csv').withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withHeader(
        'Goldfish Name', 'Goldfish Code', 'Echo Name', 'Echo Code'
    ).parse(reader).each { final CSVRecord csvRecord ->
        final Tuple2<String, String> goldfishSet =
            new Tuple2<>(csvRecord.get('Goldfish Name'), csvRecord.get('Goldfish Code'))
        final Tuple2<String, String> echoSet =
            new Tuple2<>(csvRecord.get('Echo Name'), csvRecord.get('Echo Code'))
        goldfishToEchoSets[goldfishSet] = echoSet
    }
}


// Update set names and codes as necessary

entries.each {
    final Tuple2<String, String> goldfishSet = new Tuple2<>(it.setName, it.setCode)
    if (goldfishSet in goldfishToEchoSets.keySet()) {
        final Tuple2<String, String> echoSet = goldfishToEchoSets[goldfishSet]
        it.setName = echoSet.first
        it.setCode = echoSet.second
    }
}


// Read the EchoMTG set data to use in validation of set names and codes

final Map<String, String> echoSets = [:]

new File('echo_sets.csv').withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withHeader('Name', 'Code').parse(reader).each { final CSVRecord csvRecord ->
        echoSets[csvRecord.get('Code')] = csvRecord.get('Name')
    }
}


// Add some entries to main list, such as non-English cards and Beta basics

new File('add_to_echo_import.csv').withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withHeader(
        'Name', 'Set Code', 'Count', 'Language'
    ).parse(reader).each { final CSVRecord csvRecord ->
        entries << new Entry(
            name: csvRecord.get('Name'),
            setName: echoSets[csvRecord.get('Set Code')],
            setCode: csvRecord.get('Set Code'),
            count: csvRecord.get('Count') as int,
            isFoil: false, // currently don't have any foils to add
            language: csvRecord.get('Language') ?: 'EN' // if not specified, use English
        )
    }
}


// Find entries with set names and codes not in EchoMTG set data and fail if there are any

final Set<Tuple2<String, String>> badGoldfishSets =
    entries.findAll {
        !(it.setCode in echoSets.keySet()) || (it.setName != echoSets[it.setCode])
    }.collect {
        new Tuple2<String, String>(it.setCode, it.setName)
    }.toSet()

if (badGoldfishSets) {
    final String badSetsString = badGoldfishSets.collect { "${it.second},${it.first}" }.sort().join('\n')
    throw new IllegalArgumentException("MTGGoldfish sets not in EchoMTG set data:\n${badSetsString}")
}


// There are some entries I do not want to import from MTGGoldfish to EchoMTG
// Read those entries

final List<Entry> entriesToSkip = []

new File('skip_in_echo_import.csv').withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withHeader('Name', 'Set', 'Count').parse(reader).each { final CSVRecord csvRecord ->
        entriesToSkip << new Entry(
            name: csvRecord.get('Name'),
            setCode: csvRecord.get('Set'),
            setName: '',
            count: csvRecord.get('Count') as int,
            isFoil: false, // currently don't have any foils to skip,
            language: 'EN' // currently dont' have any non-English to skip
        )
    }
}


// Remove entries from main list that are in the skip list
// May need to re-adjust count (ex: main list has count of 8, but skip list has 4)
//
// Also ensure that skip list is up-to-date
// by failing if there is something in the skip list that is not in the main list

entriesToSkip.each { final Entry entryToSkip ->
    final List<Entry> matchingEntries = entries.findAll {
        it.name == entryToSkip.name && it.setCode == entryToSkip.setCode && it.language == entryToSkip.language
    }
    if (matchingEntries.empty) {
        throw new IllegalStateException(
            "Entry in skip file, but not in MTGGoldfish collection: ${entryToSkip.name}/${entryToSkip.setCode}"
        )
    } else if (matchingEntries.size() > 1) {
        throw new IllegalStateException(
            "Matched entry in skip file more than once: ${entryToSkip.name}/${entryToSkip.setCode} -- ${matchingEntries}"
        )
    }
    final Entry matchingEntry = matchingEntries.first()
    if (matchingEntry.count < entryToSkip.count) {
        throw new IllegalStateException(
            "MTGGoldfish collection does not contain at least ${entryToSkip.count} ${entryToSkip.name}/${entryToSkip.setCode}"
        )
    } else if (matchingEntry.count == entryToSkip.count) {
        // just remove it
        entries.remove(matchingEntry)
    } else {
        // update count
        matchingEntry.count -= entryToSkip.count
    }
}


// MTGGoldfish CSV does not distinctly identify different artworks for basic lands,
// and they're generally not of much value, so just remove all non-Unstable basics
// Basic lands I care to import (ex Beta basics) are added from file elsewhere

entries.removeIf { it.name in ['Forest', 'Island', 'Mountain', 'Plains', 'Swamp', 'Wastes'] && it.setCode != 'UN3' }


// There are a number of cards that have multiple artworks in the same set
// Ex: Hymn to Tourach and High Tide in Fallen Empires
// The MTGGoldfish CSV does not distinctly identify these
// Collect these for later output and remove them from main list

final Map<String, List<String>> cardsWithMultipleArtworks = [:].withDefault { [] }

new File('cards_with_multiple_artworks.csv').withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withHeader('Set', 'Name').parse(reader).each { final CSVRecord csvRecord ->
        cardsWithMultipleArtworks[csvRecord.get('Set').trim()] << csvRecord.get('Name').trim()
    }
}

final List<Entry> entriesWithMultipleArtworks = entries.findAll {
    it.setCode in cardsWithMultipleArtworks.keySet() && it.name in cardsWithMultipleArtworks[it.setCode]
}
entries.removeAll(entriesWithMultipleArtworks)


// EchoMTG CSV import does not handle split cards
// Collect these entries for output later and remove them from main list

final List<Entry> splitCardEntries = entries.findAll { it.name.contains('/') }
entries.removeAll(splitCardEntries)


// Output entries to import to EchoMTG to stdout

CSVFormat.DEFAULT.printer().withCloseable { final CSVPrinter csvPrinter ->
    csvPrinter.printRecord('Reg Qty', 'Foil Qty', 'Name', 'Set', 'Acquired', 'Language')
    entries.sort().each { final Entry entry ->
        csvPrinter.printRecord(
            entry.isFoil ? 0 : entry.count,
            entry.isFoil ? entry.count : 0,
            entry.name,
            // Echo will import successfully if using set code, but it may import the wrong set, so use full set name
            // ex: 'Dark Ritual,CST' will import as the A25 version,
            //     but 'Dark Ritual,MMQ' will import as the correct version
            entry.setName,
            '', // acquired price field
            entry.language
        )
    }
}


// Output entries I need to handle manually to stderr

printNotImportedEntries('Cards with multiple artworks in set', entriesWithMultipleArtworks)
printNotImportedEntries('Split cards that cannot be imported to Echo', splitCardEntries)


// Output skipped entries to stderr

printNotImportedEntries('Cards skipped in import', entriesToSkip)
