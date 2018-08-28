CREATE TABLE people (
    id serial PRIMARY KEY,
    name varchar(50) NOT NULL CONSTRAINT not_empty CHECK (char_length(name) > 0)
);

--;;

CREATE UNIQUE INDEX people__name ON people (lower(name))

--;;

CREATE TABLE categories (
    id serial PRIMARY KEY,
    name varchar(50) NOT NULL CONSTRAINT not_empty CHECK (char_length(name) > 0)
);

--;;

CREATE UNIQUE INDEX categories__name ON categories (lower(name))
