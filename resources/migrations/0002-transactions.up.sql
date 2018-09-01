CREATE TABLE transactions (
    id bigserial PRIMARY KEY,
    created_at timestamp with time zone NOT NULL DEFAULT NOW(),
    updated_at timestamp with time zone NOT NULL DEFAULT NOW(),
    date date NOT NULL,
    category_id integer NOT NULL REFERENCES categories ON DELETE RESTRICT,
    title varchar(50) NOT NULL CONSTRAINT not_empty CHECK (char_length(title) > 0),
    description text NOT NULL DEFAULT '',
    tags varchar(20)[] NOT NULL DEFAULT '{}'
);

--;;

CREATE INDEX transactions__date ON transactions (date, id);

--;;

CREATE INDEX transactions__category ON transactions (category_id);

--;;

CREATE INDEX transactions__alltext ON transactions USING GIN (to_tsvector('english', title || ' ' || description));

--;;

CREATE INDEX transactions__tags ON transactions USING GIN (tags)

--;;

CREATE FUNCTION validate_transaction_update() RETURNS trigger AS $$
    BEGIN
        -- Some fields can't be changed after creation.
        NEW.created_at := OLD.created_at;

        NEW.updated_at := NOW();

        RETURN NEW;
    END
$$ LANGUAGE plpgsql;

--;;

CREATE TRIGGER validate_transaction_update BEFORE UPDATE on transactions
    FOR EACH ROW EXECUTE PROCEDURE validate_transaction_update();

--;;

CREATE TABLE splits (
    transaction_id bigint REFERENCES transactions ON DELETE CASCADE,
    person_id integer REFERENCES people ON DELETE RESTRICT,
    PRIMARY KEY (transaction_id, person_id),
    amount decimal(10, 2) NOT NULL CONSTRAINT non_zero CHECK (amount <> 0)
);

--;;

CREATE INDEX splits__person_id ON splits (person_id);
