-- Pre-aggregated totals for all person-category pairs.
CREATE TABLE IF NOT EXISTS totals (
    person_id integer REFERENCES people ON DELETE CASCADE,
    category_id integer REFERENCES categories ON DELETE CASCADE,
    PRIMARY KEY (person_id, category_id),
    amount numeric(10, 2) NOT NULL DEFAULT 0
);

--;;

CREATE INDEX IF NOT EXISTS totals__category_id ON totals (category_id);

--;;

CREATE OR REPLACE FUNCTION init_person_totals() RETURNS trigger AS $$
    BEGIN
        INSERT INTO totals (person_id, category_id)
            SELECT NEW.id, categories.id FROM categories;
        RETURN NULL;
    END
$$ LANGUAGE plpgsql;

--;;

CREATE TRIGGER init_person_totals AFTER INSERT ON people
    FOR EACH ROW EXECUTE PROCEDURE init_person_totals();

--;;

CREATE OR REPLACE FUNCTION init_category_totals() RETURNS trigger AS $$
    BEGIN
        INSERT INTO totals (person_id, category_id)
            SELECT people.id, NEW.id FROM people;
        RETURN NULL;
    END
$$ LANGUAGE plpgsql;

--;;

CREATE TRIGGER init_category_totals AFTER INSERT ON categories
    FOR EACH ROW EXECUTE PROCEDURE init_category_totals();

--;;

CREATE OR REPLACE FUNCTION aggregate_transaction(txn transactions, factor int DEFAULT 1) RETURNS void AS $$
    UPDATE totals AS t
        SET   amount = t.amount + (s.amount * factor)
        FROM  splits AS s
        WHERE (transaction_id = txn.id) AND (t.category_id = txn.category_id) AND (t.person_id = s.person_id);
$$ LANGUAGE SQL;

--;;

CREATE OR REPLACE FUNCTION update_transaction_totals_pre() RETURNS trigger AS $$
    BEGIN
        PERFORM aggregate_transaction(OLD, factor => -1);

        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        ELSE
            RETURN NEW;
        END IF;
    END
$$ LANGUAGE plpgsql;

--;;

CREATE OR REPLACE FUNCTION update_transaction_totals_post() RETURNS trigger AS $$
    BEGIN
        PERFORM aggregate_transaction(NEW);

        RETURN NULL;
    END
$$ LANGUAGE plpgsql;

--;;

CREATE TRIGGER update_transaction_totals_pre BEFORE UPDATE OR DELETE ON transactions
    FOR EACH ROW EXECUTE PROCEDURE update_transaction_totals_pre();

--;;

CREATE TRIGGER update_transaction_totals_post AFTER INSERT OR UPDATE ON transactions
    FOR EACH ROW EXECUTE PROCEDURE update_transaction_totals_post();

--;;

CREATE OR REPLACE FUNCTION aggregate_split(split splits, factor int DEFAULT 1) RETURNS void AS $$
    UPDATE totals AS t
        SET   amount = t.amount + (split.amount * factor)
        FROM  transactions AS tx
        WHERE (tx.id = split.transaction_id) AND (t.category_id = tx.category_id) AND (t.person_id = split.person_id);
$$ LANGUAGE SQL;

--;;

CREATE OR REPLACE FUNCTION update_split_totals_pre() RETURNS trigger AS $$
    BEGIN
        PERFORM aggregate_split(OLD, factor => -1);

        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        ELSE
            RETURN NEW;
        END IF;
    END
$$ LANGUAGE plpgsql;

--;;

CREATE OR REPLACE FUNCTION update_split_totals_post() RETURNS trigger AS $$
    BEGIN
        PERFORM aggregate_split(NEW);

        RETURN NULL;
    END
$$ LANGUAGE plpgsql;

--;;

CREATE TRIGGER update_split_totals_pre BEFORE UPDATE OR DELETE ON splits
    FOR EACH ROW EXECUTE PROCEDURE update_split_totals_pre();

--;;

CREATE TRIGGER update_split_totals_post AFTER INSERT OR UPDATE ON splits
    FOR EACH ROW EXECUTE PROCEDURE update_split_totals_post();

--;;

-- Computes the current total for every person-category pair. These are cached,
-- so this function is just for verifying or resetting the "totals" table.
CREATE OR REPLACE VIEW dynamic_totals AS
    SELECT p.id AS person_id, c.id AS category_id, COALESCE(SUM(s.amount), 0) AS amount
        FROM (people AS p CROSS JOIN categories AS c)
                 LEFT OUTER JOIN
             (transactions AS tx INNER JOIN splits AS s ON (tx.id = s.transaction_id))
                 ON (c.id = tx.category_id AND p.id = s.person_id)
    GROUP BY p.id, c.id;

--;;

-- Repopulates the totals table from scratch.
CREATE OR REPLACE FUNCTION reset_totals() RETURNS void AS $$
    INSERT INTO totals (person_id, category_id, amount)
        SELECT person_id, category_id, amount FROM dynamic_totals
        ON CONFLICT (person_id, category_id) DO UPDATE SET amount = EXCLUDED.amount;
$$ LANGUAGE SQL;

--;;

SELECT reset_totals();
