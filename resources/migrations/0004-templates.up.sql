CREATE TABLE templates (
    id bigserial PRIMARY KEY,
    category_id integer NOT NULL REFERENCES categories ON DELETE RESTRICT,
    title varchar(50) NOT NULL CONSTRAINT not_empty CHECK (char_length(title) > 0),
    description text NOT NULL DEFAULT '',
    tags varchar(20)[] NOT NULL DEFAULT '{}'
);

--;;

CREATE TABLE template_splits (
    template_id bigint REFERENCES templates ON DELETE CASCADE,
    person_id integer REFERENCES people ON DELETE RESTRICT,
    PRIMARY KEY (template_id, person_id),
    amount money NOT NULL CONSTRAINT non_zero CHECK (amount <> 0::money)
);
