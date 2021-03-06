
-- partitioned in test on pkey
CREATE TABLE t1 (
 PKEY          INTEGER NOT NULL,
 A_INT         INTEGER,
 A_STR  VARCHAR(10),
 PRIMARY KEY (PKEY)
);

-- replicated in test
CREATE TABLE t2 (
 PKEY          INTEGER NOT NULL,
 A_INT         INTEGER,
 A_STR  VARCHAR(10),
 PRIMARY KEY (PKEY)
);
PARTITION TABLE t2 ON COLUMN PKEY;

CREATE TABLE t3 (
 PKEY INTEGER NOT NULL,
 I3  INTEGER NOT NULL,
 I4  INTEGER NOT NULL,
 PRIMARY KEY (PKEY)
 );

 CREATE INDEX t3_TREE ON t3 (I3 DESC);

 CREATE INDEX t3_PLUS ON t3 (I3 + I4);

CREATE TABLE TSRC1 (
    G0 INT DEFAULT '0' NOT NULL,
    G1 INT DEFAULT '0' NOT NULL,
    C0 INT DEFAULT '0' NOT NULL,
    C1 FLOAT DEFAULT '0' NOT NULL,
    C2 DECIMAL DEFAULT '0' NOT NULL,
    C3 TIMESTAMP DEFAULT NOW NOT NULL,
    C4 TINYINT DEFAULT '0' NOT NULL,
    C5 BIGINT DEFAULT '0' NOT NULL,
    C6 SMALLINT DEFAULT '0' NOT NULL,
    C7 INT DEFAULT '0' NOT NULL,
    C8 INT DEFAULT '0' NOT NULL,
    C9 INT DEFAULT '0' NOT NULL,
    C10 VARCHAR DEFAULT 'abc' NOT NULL,
    C11 VARCHAR DEFAULT 'def' NOT NULL,
    PRIMARY KEY (G0, G1)
);
PARTITION TABLE TSRC1 ON COLUMN G0;

CREATE TABLE TSRC2 (
    G0 INT NOT NULL,
    G1 INT DEFAULT '0' NOT NULL
);
PARTITION TABLE TSRC2 ON COLUMN G0;

CREATE TABLE t4_view (
    A INTEGER NOT NULL,
    B INTEGER NOT NULL
);

CREATE VIEW V1
(G1, CNT, C0, C1, C2, SUMC2, C3, C4, C5, C6, C7, C8, CNTC8, C9, C10, C11)
AS
SELECT G1, COUNT(*),
       MAX(C0), MIN(C1), MAX(C2), SUM(C2), MIN(C3), MAX(C4), MIN(C5),
       MAX(C6), MIN(C7), MAX(C8), COUNT(C8), MIN(C9), MAX(C10), MIN(C11)
FROM TSRC1 GROUP BY G1;

CREATE VIEW V2
(G1, CNT, C0, C1, C2, SUMC2, C3, C4, C5, C6, C7, C8, CNTC8, C9, C10, C11)
AS
SELECT TSRC1.G1, COUNT(*),
       MAX(TSRC1.C0), MIN(TSRC1.C1), MAX(TSRC1.C2), SUM(TSRC1.C2), MIN(TSRC1.C3),
       MAX(TSRC1.C4), MIN(TSRC1.C5), MAX(TSRC1.C6), MIN(TSRC1.C7),
       MAX(TSRC1.C8), COUNT(TSRC1.C8), MIN(TSRC1.C9), MAX(TSRC1.C10), MIN(TSRC1.C11)
FROM TSRC1 JOIN TSRC2 ON TSRC1.G0 = TSRC2.G0 GROUP BY TSRC1.G1;

CREATE PROCEDURE MultiSP AS BEGIN
  insert into t1 values (?, ?, ?);
  select * from t1;
END;

CREATE PROCEDURE MultiSPSingle
PARTITION ON TABLE t2 COLUMN PKEY PARAMETER 0
AS BEGIN
  insert into t2 values (?, ?, ?);
  select * from t2;
END;

CREATE PROCEDURE MultiSPSingle1
PARTITION ON TABLE t2 COLUMN PKEY PARAMETER 4
AS BEGIN
  select * from t2 where PKEY = ? AND A_INT = ?;
  insert into t2 values (?, ?, ?);
  select * from t2;
END;

CREATE TABLE EMPLOYEES (
    LAST_NAME VARCHAR(2048) NOT NULL,
    EMP_ID INTEGER NOT NULL,
    MANAGER_ID INTEGER NOT NULL
);

CREATE PROCEDURE RCTE AS
    WITH RECURSIVE EMP_PATH(LAST_NAME, EMP_ID, MANAGER_ID, LEVEL, PATH) AS (
        SELECT LAST_NAME, EMP_ID, MANAGER_ID, 1, LAST_NAME FROM EMPLOYEES WHERE MANAGER_ID = 0
        UNION ALL
        SELECT E.LAST_NAME, E.EMP_ID, E.MANAGER_ID, EP.LEVEL+1, EP.PATH || '/' || E.LAST_NAME
          FROM EMPLOYEES E JOIN EMP_PATH EP ON E.MANAGER_ID = EP.EMP_ID
    )
    SELECT * FROM EMP_PATH a JOIN EMP_PATH b ON 1=1 WHERE a.LAST_NAME IS NOT NULL;

CREATE PROCEDURE NRCTE AS
    WITH EMP_BASE(LAST_NAME, EMP_ID, MANAGER_ID, LEVEL, PATH) AS (
        SELECT LAST_NAME, EMP_ID, MANAGER_ID, 1, LAST_NAME FROM EMPLOYEES WHERE MANAGER_ID = 0
    )
    SELECT E.LAST_NAME, E.EMP_ID, E.MANAGER_ID, EB.LEVEL+1, EB.PATH || '/' || E.LAST_NAME
      FROM EMPLOYEES E JOIN EMP_BASE EB ON E.MANAGER_ID = EB.EMP_ID;

CREATE VIEW v (A, CNTB, SUMB) AS SELECT A, COUNT(*), SUM(B) from t4_view group by A;
