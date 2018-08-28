INSERT INTO people (id, name) VALUES (1, 'Alice'), (2, 'Bob');
INSERT INTO categories (id, name) VALUES (1, 'Expenses'), (2, 'Payments');
INSERT INTO transactions (id, date, category_id, title) VALUES (1, current_date, 1, 'Rent');
INSERT INTO splits (transaction_id, person_id, amount) VALUES (1, 1, 700), (1, 2, -700);
