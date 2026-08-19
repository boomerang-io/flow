const approverGroups = [
  {
    id: "1",
    name: "Test approver 1",
    creationDate: "2022-01-05T22:50:12.204+00:00",
    approvers: [
     {
       id: "5b69f3adf170ce00018106b6",
       email: "isabela.kawabata@ibm.com",
       name: "Isabela Mayumi Kawabata Borges",
     },
     {
       id: "5b6306564bd080000168f0f0",
       email: "lucas.grimauth@ibm.com",
       name: "Lucas Grimauth Evangelista",
     }
    ]
  },
  {
   id: "2",
   name: "New amazing approver group",
   creationDate: "2022-01-05T22:50:12.204+00:00",
   approvers: [
    {
      id: "5b69f3adf170ce00018106b6",
      email: "isabela.kawabata@ibm.com",
      name: "Isabela Mayumi Kawabata Borges",
    },
    {
      id: "5b6306564bd080000168f0f0",
      email: "lucas.grimauth@ibm.com",
      name: "Lucas Grimauth Evangelista",
    },
    {
     id: "5b85516579ab9f0001097480",
     email: "amhudson@us.ibm.com",
     name: "Adrienne Hudson",
    },
    {
     id: "1b6378a84bd080000168f0f1",
     email: "mwinsby@us.ibm.com",
     name: "Megan Winsby",
    },
    {
     id: "5b5f97a105d83a00018bdbed",
     email: "mdroy@us.ibm.com",
     name: "Marcus Roy",
    },
    {
     id: "5b5f802f05d83a00018bdbeb",
     email: "benjamin.ruby@ibm.com",
     name: "Benjamin Ruby",
    },
    {
     id: "5b59176f621bbc00015656a0",
     email: "twlawrie@us.ibm.com",
     name: "Tyson Lawrie",
    },
    {
     id: "5b590e92621bbc000156569c",
     email: "gchickma@us.ibm.com",
     name: "Glen Hickman",
    }
   ]
   },
   {
     id: "3",
     name: "Empty group",
     creationDate: "2022-01-05T22:50:12.204+00:00",
     approvers: []
   },
   {
     type: "group",
     id: "c199f457-589a-4ca3-c23a-e7f69e4bec99",
     creationDate: "2022-01-05T22:50:12.204+00:00",
     approvalsRequired: 1,
     name: "Test approver group",
     approvers: [
         {
             "type": "individual",
             "id": "59aebd0b7424530fce952fdd",
             "name": "Tyson Lawrie",
             "email": "boomerang@us.ibm.com",
             "required": false
         },
         {
             "type": "individual",
             "id": "59aebd0b7424530fce952fdc",
             "name": "Glen Hickman",
             "email": "gchickma@us.ibm.com",
             "required": false
         }
     ]
 }
 ];
 
 export default approverGroups;
 