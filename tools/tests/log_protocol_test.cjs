const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { BinaryCommandIds, BinaryFieldIds, BinaryProtocolParser } = require('../web_debug/web/js/protocol.js');

const root = path.resolve(__dirname, '../..');
const cpp = fs.readFileSync(path.join(root, 'firmware/src/protocol/BinaryFieldIds.h'), 'utf8');
const kotlin = fs.readFileSync(path.join(root, 'app/app/src/main/java/com/nndai/myhome/protocol/BinaryFieldIds.kt'), 'utf8');
for (const [name, wire, id] of [
  ['LogSchema', 'logSchema', 180], ['Generation', 'generation', 181], ['BootId', 'bootId', 182],
  ['Revision', 'revision', 183], ['CatalogRevision', 'catalogRevision', 184],
  ['OldestSegmentId', 'oldestSegmentId', 185], ['SegmentId', 'segmentId', 186],
  ['SnapshotId', 'snapshotId', 187], ['SnapshotSize', 'snapshotSize', 188],
  ['NextOffset', 'nextOffset', 189], ['LogicalBytes', 'logicalBytes', 190],
  ['DroppedRecords', 'droppedRecords', 191], ['StorageState', 'storageState', 192],
  ['MinEpoch', 'minEpoch', 193], ['MaxEpoch', 'maxEpoch', 194], ['Sealed', 'sealed', 195],
  ['ScanEntries', 'scanEntries', 196], ['ReadChunks', 'readChunks', 197],
  ['QueueDepth', 'queueDepth', 198], ['QueueHighWater', 'queueHighWater', 199]
]) {
  assert.match(cpp, new RegExp(`\\b${name} = ${id}\\b`));
  assert.match(kotlin, new RegExp(`Int = ${id}\\b`));
  assert.equal(BinaryFieldIds.getId(wire), id);
  assert.equal(BinaryFieldIds.getName(id), wire);
}
for (const [command, id] of [['getLogState', 36], ['syncLog', 37], ['listLogSegments', 38], ['deleteLogSegment', 39]]) {
  assert.equal(BinaryCommandIds.stringToCommandId(command), id);
  assert.equal(BinaryCommandIds.commandIdToString(id), command);
}
const response = { cmd: 'syncLog', generation: 0xfffffffe, bootId: 12, stream: 2,
  segmentId: 55, offset: 24, nextOffset: 28, snapshotId: 55, snapshotSize: 28,
  data: new Uint8Array([0, 82, 255, 10]), status: 'ok', more: false };
const parsed = BinaryProtocolParser.parse(BinaryProtocolParser.serialize(response));
for (const key of Object.keys(response).filter(key => key !== 'data')) assert.equal(parsed[key], response[key]);
assert.deepEqual(Array.from(parsed.data), Array.from(response.data));
console.log('C++ / Kotlin / browser IDs and binary snapshot round trip passed');
