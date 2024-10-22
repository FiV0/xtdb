import assert from 'assert';
import postgres from 'postgres';
import pg from 'pg'
import tjs from 'transit-js';
import * as uuid from 'uuid';

const transitReadHandlers = {
  'time/zoned-date-time': (s) => new Date(s.replace(/\[.+\]$/, '')),
}

let sql;

beforeEach (async () => {
  sql = postgres({
    host: process.env.PG_HOST || "localhost",
    port: process.env.PG_PORT || 5439,
    database: uuid.v4().toString(),
    fetch_types: false, // currently required https://github.com/xtdb/xtdb/issues/3607
    types: {
      bool: {to: 16},
      int: {
        to: 20,
        from: [23, 20], // int4, int8
        parse: parseInt
      },
      transit: {
        to: 16384,
        from: [16384],
        serialize: (v) => tjs.writer('json').write(v),
        parse: (v) => tjs.reader('json', { handlers: transitReadHandlers }).read(v)
      }
    }
  })

  await sql`SELECT 1` // HACK https://github.com/porsager/postgres/issues/751
})

afterEach(async () => {
  await sql.end()
})

describe("connects to XT", function() {
  it("should return the inserted row", async () => {
    const conn = await sql.reserve()

    try {
      await conn`INSERT INTO foo (_id, msg) VALUES (${conn.typed.int(1)}, 'Hello world!')`

      assert.deepStrictEqual([...await conn`SELECT _id, msg FROM foo`],
                             [{_id: 1, msg: 'Hello world!'}])

    } finally {
      await conn.release()
    }
  })

  it("JSON-like types can be roundtripped", async () => {
    const conn = await sql.reserve()
    try {
      await conn`INSERT INTO foo2 (_id, bool) VALUES (1, ${conn.typed.bool(true)})`

      assert.deepStrictEqual([{_id: 1, bool: true}],
                             [...await conn`SELECT * FROM foo2`])
    } finally {
      await conn.release()
    }

  })

  it("should round-trip JSON", async () => {
    const conn = await sql.reserve()
    try {
      await conn`INSERT INTO foo (_id, json) VALUES (${conn.typed.int(2)}, ${conn.json({a: 1})})`

      assert.deepStrictEqual([...await conn`SELECT _id, json FROM foo WHERE _id = 2`],
                             [{_id: 2, json: {a: 1}}])

      assert.deepStrictEqual([...await conn`SELECT _id, (json).a FROM foo WHERE _id = 2`],
                             [{_id: 2, a: 1}])
    } finally {
      await conn.release()
    }
  })

  it("should round-trip transit", async () => {
    const conn = await sql.reserve()

    try {
      await conn`SET fallback_output_format='transit'`

      const ts = new Date('2020-01-01')

      await conn`INSERT INTO foo (_id, transit) VALUES (${conn.typed.int(2)}, ${conn.typed.transit({ts})})`

      const res = await conn`SELECT _id, transit FROM foo WHERE _id = 2`

      // HACK can't figure out how to get it doing this automatically
      res[0].transit = tjs.mapToObject(res[0].transit)

      assert.deepStrictEqual([...res],
                             [{_id: 2, transit: { ts }}])

      assert.deepStrictEqual([...await conn`SELECT _id, (transit).ts + INTERVAL 'P2D' AS third FROM foo WHERE _id = 2`],
                             [{_id: 2, third: new Date("2020-01-03T00:00Z")}])

    } finally {
      await conn.release()
    }
  })

  it("accepts tagged numbers as floats/ints", async () => {
    const conn = await sql.reserve()

    try {
      await conn`INSERT INTO tagged_nums RECORDS {_id: 1, nest: ${conn.typed.transit({a: 1, b: 1.0, c: 1.1, d: tjs.tagged('f64', 1)})}}`

      let res = await conn`SELECT _id, nest FROM tagged_nums`

      assert.deepStrictEqual([...res], [{_id: 1, nest: {a: 1, b: 1, c: 1.1, d: 1}}])

      res = await conn`select * from information_schema.columns WHERE table_name = 'tagged_nums' AND column_name = 'nest'`
      const type = res[0].data_type;
      assert.equal('[:struct {a :i64, b :i64, c :f64, d :f64}]', type, `data_type is actually ${type}`);
    } finally {
      await conn.release()
    }
  })

  it("should round-trip top-level transit via RECORDS", async () => {
    const conn = await sql.reserve()

    try {
      await conn`SET fallback_output_format='transit'`

      const m = {'_id': 2, transit: {'d': new Date('2020-01-01')}}

      await conn`INSERT INTO top_level_records RECORDS ${conn.typed.transit(m)}`

      const res = await conn`SELECT _id, transit FROM top_level_records WHERE _id = 2`

      // HACK can't figure out how to get it doing this automatically
      res[0].transit = tjs.mapToObject(res[0].transit)

      assert.deepStrictEqual([...res], [m])

      assert.deepStrictEqual([...await conn`SELECT _id, (transit).d + INTERVAL 'P2D' AS third FROM top_level_records WHERE _id = 2`],
                             [{_id: 2, third: new Date("2020-01-03T00:00Z")}])

    } finally {
      await conn.release()
    }
  })
})

describe("connects to XT via node-postgres", function() {
  it("test type casts in parameters", async () => {
    const { Client } = pg
    const client = new Client({
      host: 'localhost',
      port: process.env.PG_PORT || 5439,
    })

    try {
      await client.connect()

      var res
      res = await client.query('SELECT $1::text as message', ['Hello world!'])
      assert.deepStrictEqual(res.rows[0].message, 'Hello world!')

      let d = new Date()
      res = await client.query('SELECT $1::DOUBLE PRECISION AS f, $2::TIMESTAMPTZ AS d', [42, d])
      assert.deepStrictEqual(res.rows[0].f, 42.0)
      assert.deepStrictEqual(res.rows[0].d, d)

      await client.query('INSERT INTO docs(_id, foo) VALUES ($1::INT, $2::FLOAT)', [1, 42.0])
      res = await client.query('SELECT * FROM docs')
      assert.deepStrictEqual(res.rows, [{_id: 1, foo: 42.0}])

      await assert.rejects(
        async () => {
          await client.query('INSERT INTO docs(_id, foo) VALUES ($1, $2::FLOAT)', [1, 42.0]);
        },
        /^error: Missing types for params/
      );



    } finally {
      await client.end()
    }
  })
})
