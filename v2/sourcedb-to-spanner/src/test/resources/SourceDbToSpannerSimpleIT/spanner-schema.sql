CREATE TABLE IF NOT EXISTS SimpleTable (
  id INT64 NOT NULL,
  name STRING(200),
) PRIMARY KEY(id);

CREATE TABLE IF NOT EXISTS StringTable (
  id INT64 NOT NULL,
  name STRING(200),
) PRIMARY KEY(name);
